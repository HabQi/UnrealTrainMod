/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import com.unrealtrainmod.client.compat.iris.IrisRenderStats;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.model.RailCatalog;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Independent opaque pass; static geometry is reused, with budgeted light updates. */
public final class TrackWorldRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrackWorldRenderer.class);
    private static final int BUILDS_PER_FRAME = 4;
    private static final int UPLOADS_PER_FRAME = 4;
    private static final long BUILD_BUDGET_NANOS = 2_000_000L;
    private static final long SLOW_BUILD_NANOS = 8_000_000L;
    private record SlowBuild(String resource, String type) {}
    private static final Set<SlowBuild> REPORTED_SLOW_BUILDS = new HashSet<>();

    /** Geometry creation and page packing share the task/time budget; cached draws do not consume it. */
    static final class BuildBudget {
        private int remaining = BUILDS_PER_FRAME;
        private long elapsed;
        private TrackRenderStats stats;

        private BuildBudget(TrackRenderStats stats) { this.stats = stats; }

        boolean available() {
            // Leave one task for pages already requested by this view, rather than spending every slot on new sources.
            int reserved = TrackStaticBuffers.WORLD.needsPageBuild() ? 1 : 0;
            if (remaining > reserved && elapsed < BUILD_BUDGET_NANOS) return true;
            if (stats != null) stats.budgetSkipped();
            return false;
        }

        boolean pageAvailable() {
            if (remaining > 0 && elapsed < BUILD_BUDGET_NANOS) return true;
            if (stats != null) stats.budgetSkipped();
            return false;
        }

        void pageFinished(long start) {
            remaining--;
            elapsed += System.nanoTime() - start;
        }

        private void finished(long start, String modelId, UUID id, int x, int z, String type) {
            long nanos = System.nanoTime() - start;
            remaining--;
            elapsed += nanos;
            if (stats != null) stats.built(nanos);
            if (nanos <= SLOW_BUILD_NANOS) return;
            var definition = RailCatalog.find(modelId == null ? null : ResourceLocation.parse(modelId));
            String resource = definition == null ? (modelId == null ? "native" : modelId)
                    : definition.source().model().toString();
            if (stats != null) stats.slowBuild(nanos, resource);
            if (REPORTED_SLOW_BUILDS.add(new SlowBuild(resource, type))) {
                LOGGER.warn("Slow track build: resource={} model={} track={} chunk={},{} type={} elapsed={}ms",
                        resource, modelId, id, x, z, type, nanos / 1_000_000.0);
            }
        }
    }

    static void resetSlowBuilds() { REPORTED_SLOW_BUILDS.clear(); }

    private static BuildBudget frameBudget = new BuildBudget(null);
    private static int frameUploads = UPLOADS_PER_FRAME;
    private static double frameTime = Double.NaN;
    private static long frame;
    private static final TrackDrawQueue DRAW_QUEUE = new TrackDrawQueue();

    static void releaseDrawQueue() { DRAW_QUEUE.close(); }

    public static void beginFrame() {
        frame++;
        NativeTurnoutLoading.beginFrame(frame);
        frameBudget = new BuildBudget(null);
        frameUploads = UPLOADS_PER_FRAME;
        frameTime = Double.NaN;
    }

    private record NativeDynamic(NativeTurnoutRenderState state, Set<Long> chunks) {}
    private TrackWorldRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS || IrisCompat.shadowPass()) return;
        renderView(event, false);
    }

    public static void renderShadow(RenderLevelStageEvent event) { renderView(event, true); }

    private static void renderView(RenderLevelStageEvent event, boolean shadow) {
        var cache = ClientTrackCache.INSTANCE;
        var level = Minecraft.getInstance().level;
        if (level == null || cache.world() != level || (cache.entries.isEmpty() && cache.assemblies.isEmpty())) return;
        RenderSystem.assertOnRenderThread();
        boolean iris = IrisCompat.active();
        NativeTurnoutLoading.view(shadow);
        var stats = TrackRenderStats.begin(level, shadow ? "shadow" : "world", iris ? "IrisCachedGroups" : "materialGroups");
        long stageStart = stats == null ? 0 : System.nanoTime();
        var queue = DRAW_QUEUE;
        queue.begin();
        TrackStaticBuffers.WORLD.beginView();
        var budget = frameBudget; budget.stats = stats;
        int uploads = frameUploads;
        if (Double.isNaN(frameTime)) frameTime = cache.time(event.getPartialTick().getGameTimeDeltaPartialTick(level.tickRateManager().runsNormally()));
        double time = frameTime;
        var dynamic = new ArrayList<ClientTrackCache.TurnoutEntry>();
        var nativeDynamic = new ArrayList<NativeDynamic>();
        var camera = event.getCamera().getPosition();
        var modelView = new Matrix4f();
        try (var metrics = iris ? IrisRenderStats.begin(IrisRenderStats.TRACK, shadow) : null;
             var irisScope = iris ? new IrisDrawScope() : null;
             var state = iris ? null : new TrackRenderState(stats)) {
            cache.flushScriptFailures();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            for (var entry : cache.entries.values()) {
                var chunks = TrackPreparation.loaded(entry.chunks, level);
                boolean inView = chunks.stream().anyMatch(chunk -> event.getFrustum().isVisible(entry.broadBounds(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))));
                if (!inView) {
                    if (entry.preparation != null) TrackPreparation.track(entry, ClientRailModelManager.INSTANCE.model(entry.data.modelId()), level);
                    continue;
                }
                var model = ClientRailModelManager.INSTANCE.model(entry.data.modelId());
                if (entry.staticSnapshot == null && model != null && model.script() != null && !model.script().failed()) {
                    if (shadow || !budget.available()) {
                        uploads = drawRetired(entry, chunks, stats, queue, event, level, camera, modelView, uploads);
                        continue;
                    }
                    long start = System.nanoTime();
                    try { prepareTrackScript(entry, model); }
                    finally { budget.finished(start, entry.data.modelId(), entry.data.id(), 0, 0, "railStaticCapture"); }
                }
                boolean complete = TrackPreparation.track(entry, model, level);
                boolean ready = stage(entry.meshes, chunks, level, stats, TrackRenderStats.STRUCTURE_TRACK);
                if (entry.visibilityFrame != frame) {
                    entry.visibilityFrame = frame;
                    entry.visible = complete && ready;
                }
                if (!entry.visible) {
                    if (stats != null) stats.pending();
                    uploads = drawRetired(entry, chunks, stats, queue, event, level, camera, modelView, uploads);
                    continue;
                }
                entry.retired.values().forEach(TrackRenderMesh::close); entry.retired.clear();
                for (long chunk : chunks) {
                    if (stats != null) stats.chunk();
                    uploads = draw(stats, queue, event, level, camera, modelView, uploads, entry.meshes.get(chunk), TrackRenderStats.STRUCTURE_TRACK);
                }
            }
            for (var entry : cache.assemblies.values()) {
                if (!entry.rendersModel()) continue;
                if (!event.getFrustum().isVisible(entry.broadBounds())) {
                    if (entry.preparation != null) TrackPreparation.assembly(entry, ClientRailModelManager.INSTANCE.model(entry.data.modelId()), level);
                    if (entry.script != null && entry.script.preparation != null) prepareTurnoutMeshes(entry, budget, level);
                    continue;
                }
                var model = ClientRailModelManager.INSTANCE.model(entry.data.modelId());
                NativeTurnoutLoading.observe(entry);
                boolean hadNative = entry.nativeState != null || entry.crossoverState != null || entry.diamondState != null;
                boolean complete = entry.scriptActive || TrackPreparation.assembly(entry, model, level);
                if (!hadNative && stats != null) {
                    if (entry.nativeState != null) stats.nativeLayout(entry.nativeState.layout.samples(), entry.nativeState.layout.parts().size());
                    else if (entry.crossoverState != null) stats.nativeLayout(entry.crossoverState.layout.samples(), entry.crossoverState.layout.partCount());
                    else if (entry.diamondState != null) stats.nativeLayout(entry.diamondState.layout.samples(), entry.diamondState.layout.partCount());
                }
                var chunks = TrackPreparation.loaded(entry.chunks, level);
                var natives = new ArrayList<NativeTurnoutRenderState>();
                if (entry.nativeState != null) {
                    if (entry.nativeState.loading != entry.loading) entry.nativeState.attachLoading(entry.loading);
                    entry.nativeState.movement(entry.animations.get("main").position(time));
                    natives.add(entry.nativeState);
                }
                if (entry.crossoverState != null) {
                    if (entry.crossoverState.loading != entry.loading) entry.crossoverState.attachLoading(entry.loading);
                    for (var control : entry.crossoverState.switches.entrySet()) {
                        control.getValue().movement(entry.animations.get(control.getKey()).position(time));
                        natives.add(control.getValue());
                    }
                }
                boolean scripted = entry.script != null && model != null && model.scripted() && !entry.scriptFailed;
                if (scripted && !entry.prepared() && !shadow && entry.scriptPrepareFrame != frame && budget.available()) {
                    entry.scriptPrepareFrame = frame;
                    long start = System.nanoTime();
                    try { prepareAssemblyScript(entry.script, model, time); }
                    finally { budget.finished(start, entry.data.modelId(), entry.data.id(), 0, 0, "turnoutScriptPrepare"); }
                }
                if (scripted && !entry.scriptFailed && entry.prepared()) {
                    boolean scriptsReady = prepareTurnoutMeshes(entry, budget, level);
                    scriptsReady &= stage(entry.script.meshes, chunks, level, stats);
                    if (!entry.scriptFailed) scriptsReady &= entry.script.parts.ready(entry.script.currentSnapshot, iris, stats);
                    if (scriptsReady && !entry.scriptFailed && !entry.scriptActive && !entry.scriptActivating) {
                        entry.scriptActivating = true;
                        int version = entry.generation;
                        com.unrealtrainmod.client.ModelUploads.commit(() -> {
                            if (version != entry.generation) return;
                            entry.scriptActivating = false;
                            if (entry.prepared() && !entry.scriptFailed) entry.activateScripts();
                        });
                    }
                }
                boolean ready = entry.scriptActive ? stage(entry.script.meshes, chunks, level, stats)
                        && entry.script.parts.ready(entry.script.currentSnapshot, iris, stats)
                        : stageGroups(entry.meshes, chunks, level, stats);
                if (!entry.scriptActive) {
                    for (var group : entry.tongues.values()) for (var mesh : group)
                        ((TrackMorphMesh)mesh).movement(entry.animations.get("main").position(time));
                    ready &= stageGroups(entry.tongues, chunks, level, stats);
                }
                for (var nativeState : natives) ready &= nativeState.parts.ready(nativeState.snapshot, iris, stats);
                if (entry.visibilityFrame != frame) {
                    entry.visibilityFrame = frame;
                    entry.visible = (entry.scriptActive || complete) && ready;
                }
                if (!entry.visible) { if (stats != null) stats.pending(); continue; }
                if (entry.scriptActive) {
                    dynamic.add(entry.script);
                    for (long chunk : chunks) uploads = draw(stats, queue, event, level, camera, modelView, uploads,
                            entry.script.meshes.get(chunk), TrackRenderStats.STRUCTURE_ASSEMBLY);
                    continue;
                }
                for (var nativeState : natives) {
                    if (!prepareNativeEndpoint(nativeState, entry.chunks, event, level, queue, budget, shadow, stats))
                        nativeDynamic.add(new NativeDynamic(nativeState, entry.chunks));
                }
                for (long chunk : chunks) {
                    if (stats != null) stats.chunk();
                    var fixed = entry.meshes.get(chunk);
                    if (fixed != null) uploads = drawAll(stats, queue, event, level, camera, modelView, uploads, fixed);
                    var moving = entry.tongues.get(chunk);
                    if (moving != null) {
                        for (var mesh : moving) ((TrackMorphMesh)mesh).movement(entry.animations.get("main").position(time));
                        uploads = drawAll(stats, queue, event, level, camera, modelView, uploads, moving);
                    }
                }
                if (entry.nativeFailed && stats != null) stats.nativeFallback("layout_or_resource");
            }
            if (iris) {
                for (var entry : nativeDynamic) uploads = entry.state.parts.drawIris(entry.state.snapshot, null, 1,
                        entry.chunks, event, level, queue, uploads, stats, budget, shadow);
                for (var entry : dynamic) uploads = entry.parts.drawIris(entry.currentSnapshot, entry.previousSnapshot,
                        entry.snapshotFraction(time), entry.chunks, event, level, queue, uploads, stats, budget, shadow);
            }
            uploads = TrackStaticBuffers.WORLD.prepare(level, budget, uploads, stats);
            queue.resolve(TrackStaticBuffers.WORLD, stats);
            TrackStaticBuffers.WORLD.collectStats(stats);
            if (iris) irisScope.beginTrackBatch();
            queue.draw(state, event.getProjectionMatrix(), stats);
            if (!iris) {
                for (var entry : nativeDynamic) uploads = entry.state.parts.draw(state, entry.state.snapshot, null, 1,
                        entry.chunks, event, level, uploads, stats);
                for (var entry : dynamic) uploads = entry.parts.draw(state, entry.currentSnapshot, entry.previousSnapshot,
                        entry.snapshotFraction(time), entry.chunks, event, level, uploads, stats);
            }
            cache.collectStats(stats, time);
        } finally {
            queue.clear(); frameUploads = uploads;
            if (stats != null) { stats.stage(System.nanoTime() - stageStart); stats.endFrame(); }
        }
        if (stats != null) stats.logIfDue();
    }

    private static boolean stage(java.util.Map<Long, ? extends TrackRenderMesh> meshes, Set<Long> chunks,
                                 ClientLevel level, TrackRenderStats stats) {
        return stage(meshes, chunks, level, stats, TrackRenderStats.STRUCTURE_ASSEMBLY);
    }
    private static boolean stage(java.util.Map<Long, ? extends TrackRenderMesh> meshes, Set<Long> chunks,
                                 ClientLevel level, TrackRenderStats stats, int structure) {
        boolean ready = meshes.keySet().containsAll(chunks);
        for (long chunk : chunks) {
            var mesh = meshes.get(chunk);
            if (mesh != null) ready &= stage(mesh, level, stats, structure);
        }
        return ready;
    }
    private static boolean stageGroups(java.util.Map<Long, List<TrackRenderMesh>> groups, Set<Long> chunks,
                                       ClientLevel level, TrackRenderStats stats) {
        boolean ready = true;
        for (long chunk : chunks) {
            var group = groups.get(chunk);
            if (group != null) for (var mesh : group) ready &= stage(mesh, level, stats);
        }
        return ready;
    }
    private static boolean stage(TrackRenderMesh mesh, ClientLevel level, TrackRenderStats stats) {
        return stage(mesh, level, stats, TrackRenderStats.STRUCTURE_ASSEMBLY);
    }
    private static boolean stage(TrackRenderMesh mesh, ClientLevel level, TrackRenderStats stats, int structure) {
        if (mesh.isEmpty()) return true;
        boolean textures = mesh.texturesReady();
        if (mesh instanceof TrackStaticMesh fixed) {
            TrackStaticBuffers.WORLD.request(fixed, structure);
            return textures && TrackStaticBuffers.WORLD.ready(fixed);
        }
        var moving = (TrackMorphMesh)mesh;
        moving.upload(level, frameUploads, stats);
        return textures && moving.isUploaded();
    }

    private static void prepareAssemblyScript(ClientTrackCache.TurnoutEntry entry, ClientRailModelManager.Model model, double time) {
        if (model.script() == null) {
            entry.owner.failScripts(entry, "script unavailable", null);
            return;
        }
        String phase = entry.assemblyScript == null ? "script init"
                : entry.staticSnapshot == null ? "static capture" : "dynamic capture";
        try {
            if (entry.assemblyScript == null) {
                entry.assemblyScript = model.script().newTurnout(entry.view, entry.animation.position(time));
                return;
            }
            if (entry.staticSnapshot == null) {
                entry.staticSnapshot = entry.assemblyScript.captureAssemblyStatic(entry.animation.position(time),
                        model.scaleForGauge(entry.owner.data.gauge()), model.period());
                if (entry.staticSnapshot == null) entry.owner.failScripts(entry, "static capture", null);
                return;
            }
            var a = entry.view.origin();
            var anchor = BlockPos.containing(a.x(), a.y() + 0.0625, a.z());
            entry.parts = new RailPartRenderer(new TrackVector(
                    anchor.getX(), anchor.getY(), anchor.getZ()), Math.floor(a.y() / 16) * 16);
            entry.scriptAttempted = true;
            entry.captureDynamic(time);
        } catch (IOException | RuntimeException | LinkageError exception) {
            entry.owner.failScripts(entry, phase, exception);
        }
    }

    /** Stage the ordinary-turnout static replay before replacing its fallback appearance. */
    private static boolean prepareTurnoutMeshes(ClientTrackCache.AssemblyEntry entry, BuildBudget budget, ClientLevel level) {
        var turnout = entry.script;
        var chunks = TrackPreparation.loaded(entry.chunks, level);
        if (turnout.meshes.keySet().containsAll(chunks)) return true;
        if (turnout.preparation == null) {
            var missing = new HashSet<>(chunks); missing.removeAll(turnout.meshes.keySet());
            var snapshot = turnout.staticSnapshot;
            turnout.preparation = com.unrealtrainmod.client.ModelPreparation.geometry(() -> {
                var result = new java.util.LinkedHashMap<Long, com.unrealtrainmod.track.mesh.RailTrackMeshData>();
                for (long chunk : missing) result.put(chunk, snapshot.build(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
                return java.util.Map.copyOf(result);
            }, values -> values.values().stream().mapToLong(TrackPreparation::size).sum(), ignored -> {});
        }
        if (turnout.preparation == null || !turnout.preparation.ready()) return false;
        try {
            var prepared = turnout.preparation.take();
            for (var value : prepared.entrySet()) if (entry.chunks.contains(value.getKey()))
                turnout.meshes.put(value.getKey(), new RailTrackGpuMesh(value.getValue()));
        } catch (RuntimeException exception) { entry.failScripts(turnout, "static replay", exception); return false; }
        finally {
            if (turnout.preparation != null) { turnout.preparation.close(); turnout.preparation = null; }
        }
        return turnout.meshes.keySet().containsAll(chunks);
    }

    private static boolean prepareNativeEndpoint(NativeTurnoutRenderState nativeState, Set<Long> chunks,
                                                  RenderLevelStageEvent event, ClientLevel level, TrackDrawQueue queue,
                                                  BuildBudget budget, boolean shadow, TrackRenderStats stats) {
        if (!nativeState.prepareEndpoint(frame, chunks, level, budget, shadow, stats)) return false;
        var camera = event.getCamera().getPosition();
        for (var entry : nativeState.endpointMeshes().entrySet()) {
            long chunk = entry.getKey();
            if (!chunks.contains(chunk) || !level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue;
            var mesh = entry.getValue().mesh();
            if (stats != null) stats.candidate();
            if (mesh.isEmpty() || !event.getFrustum().isVisible(mesh.bounds())) continue;
            var origin = mesh.origin();
            var view = new Matrix4f(event.getModelViewMatrix()).translate((float)(origin.x()-camera.x),
                    (float)-camera.y, (float)(origin.z()-camera.z));
            queue.addStatic(mesh, view, TrackRenderStats.STRUCTURE_ASSEMBLY);
            if (stats != null) stats.visible();
        }
        return true;
    }

    private static int drawRetired(ClientTrackCache.Entry entry, Set<Long> chunks, TrackRenderStats stats, TrackDrawQueue queue,
                                   RenderLevelStageEvent event, ClientLevel level, Vec3 camera, Matrix4f modelView, int uploads) {
        for (long chunk : chunks) {
            var old = entry.retired.get(chunk);
            if (old != null) uploads = draw(stats, queue, event, level, camera, modelView, uploads, old, TrackRenderStats.STRUCTURE_TRACK);
        }
        return uploads;
    }

    private static int drawAll(TrackRenderStats stats, TrackDrawQueue queue, RenderLevelStageEvent event,
                               ClientLevel level, Vec3 camera, Matrix4f modelView, int uploads,
                               List<TrackRenderMesh> group) {
        for (var mesh : group) {
            uploads = draw(stats, queue, event, level, camera, modelView, uploads, mesh,
                    TrackRenderStats.STRUCTURE_ASSEMBLY);
        }
        return uploads;
    }

    private static int draw(TrackRenderStats stats, TrackDrawQueue queue, RenderLevelStageEvent event,
                            ClientLevel level, Vec3 camera, Matrix4f modelView, int uploads,
                            TrackRenderMesh mesh, int structure) {
        if (stats != null) stats.candidate();
        if (mesh.isEmpty()) {
            if (stats != null) stats.empty();
            return uploads;
        }
        if (!event.getFrustum().isVisible(mesh.bounds())) return uploads;
        if (stats != null) stats.visible();
        if (mesh instanceof TrackStaticMesh fixed) {
            var origin = mesh.origin();
            TrackStaticBuffers.WORLD.request(fixed, structure);
            modelView.set(event.getModelViewMatrix()).translate((float)(origin.x() - camera.x),
                    (float)-camera.y, (float)(origin.z() - camera.z));
            long start = stats == null ? 0 : System.nanoTime();
            queue.addStatic(fixed, new Matrix4f(modelView), structure);
            if (stats != null) stats.grouped(System.nanoTime() - start);
        } else {
            var moving = (TrackMorphMesh)mesh;
            uploads -= moving.upload(level, uploads, stats);
            if (!moving.isUploaded()) {
                if (stats != null) stats.pending();
                return uploads;
            }
            var origin = moving.origin();
            modelView.set(event.getModelViewMatrix()).translate((float)(origin.x() - camera.x),
                    (float)(origin.y() - camera.y), (float)(origin.z() - camera.z));
            long start = stats == null ? 0 : System.nanoTime();
            moving.enqueue(queue, new Matrix4f(modelView), structure);
            if (stats != null) stats.grouped(System.nanoTime() - start);
        }
        return uploads;
    }

    private static void prepareTrackScript(ClientTrackCache.Entry entry, ClientRailModelManager.Model model) {
        var start = entry.geometry.sample(0).position();
        var point = BlockPos.containing(start.x(), start.y() + 0.0625, start.z());
        entry.staticSnapshot = model.script().captureStatic(entry.geometry, point,
                model.scaleForGauge(entry.data.gauge()), model.period());
        if (entry.staticSnapshot == null) ClientTrackCache.INSTANCE.invalidateScriptedModel(model);
    }
}
