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
import com.unrealtrainmod.track.mesh.TrackMeshBuilder;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import com.unrealtrainmod.track.assembly.TrackAssemblyType;
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
    static BuildBudget previewBudget() { return frameBudget; }

    static void renderNativePreview(RenderLevelStageEvent event, NativeTurnoutRenderState preview,
                                    java.util.Map<Long, TrackStaticMesh> meshes, Set<Long> chunks,
                                    TrackStaticBuffers buffers, long layoutNanos) {
        var level = Minecraft.getInstance().level;
        boolean iris = IrisCompat.active();
        var stats = TrackRenderStats.begin(level, "preview", "native-r11");
        long start = stats == null ? 0 : System.nanoTime() - layoutNanos;
        var budget = frameBudget;
        budget.stats = stats;
        if (stats != null && layoutNanos > 0) {
            stats.nativeLayout(preview.layout.samples(), preview.layout.parts().size());
            stats.built(layoutNanos);
        }
        int uploads = frameUploads;
        var queue = DRAW_QUEUE;
        queue.begin(); buffers.beginView();
        var camera = event.getCamera().getPosition();
        var modelView = new Matrix4f();
        try (var scope = iris ? new IrisDrawScope() : null;
             var state = iris ? null : new TrackRenderState(stats)) {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            for (long chunk : chunks) {
                if (!level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue;
                var mesh = meshes.get(chunk);
                if (mesh == null) {
                    if (!budget.available()) continue;
                    long buildStart = System.nanoTime();
                    try { mesh = preview.build(chunk); meshes.put(chunk, mesh); }
                    finally {
                        budget.pageFinished(buildStart);
                        if (stats != null) stats.built(System.nanoTime() - buildStart);
                    }
                }
                if (!mesh.isEmpty() && event.getFrustum().isVisible(mesh.bounds())) {
                    buffers.request(mesh, TrackRenderStats.STRUCTURE_ASSEMBLY);
                    var origin = mesh.origin();
                    modelView.set(event.getModelViewMatrix()).translate((float)(origin.x() - camera.x),
                            (float)-camera.y, (float)(origin.z() - camera.z));
                    queue.addStatic(mesh, new Matrix4f(modelView), TrackRenderStats.STRUCTURE_ASSEMBLY);
                    if (stats != null) { stats.candidate(); stats.visible(); }
                }
            }
            if (iris) uploads = preview.parts.drawIris(preview.snapshot, null, 1, chunks, event, level,
                    queue, uploads, stats, budget, false);
            uploads = buffers.prepare(level, budget, uploads, stats);
            queue.resolve(buffers, stats);
            buffers.collectStats(stats);
            if (iris) scope.beginTrackBatch();
            queue.draw(state, event.getProjectionMatrix(), stats);
            if (!iris) uploads = preview.parts.draw(state, preview.snapshot, null, 1, chunks, event, level, uploads, stats);
        } finally {
            queue.clear(); frameUploads = uploads;
            if (stats != null) { stats.stage(System.nanoTime() - start); stats.endFrame(); stats.logIfDue(); }
        }
    }

    public static void beginFrame() {
        frame++;
        frameBudget = new BuildBudget(null);
        frameUploads = UPLOADS_PER_FRAME;
        frameTime = Double.NaN;
    }

    private TrackWorldRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS || IrisCompat.shadowPass()) return;
        renderView(event, false);
    }

    public static void renderShadow(RenderLevelStageEvent event) { renderView(event, true); }

    private static void renderView(RenderLevelStageEvent event, boolean shadow) {
        ClientTrackCache cache = ClientTrackCache.INSTANCE;
        var level = Minecraft.getInstance().level;
        if (level == null || cache.world() != level || (cache.entries.isEmpty() && cache.assemblies.isEmpty())) return;
        RenderSystem.assertOnRenderThread();
        boolean iris = IrisCompat.active();
        TrackRenderStats stats = TrackRenderStats.begin(level, shadow ? "shadow" : "world",
                iris ? "IrisCachedGroups" : "materialGroups");
        long stageStart = stats == null ? 0L : System.nanoTime();
        TrackDrawQueue queue = DRAW_QUEUE;
        queue.begin();
        TrackStaticBuffers.WORLD.beginView();
        var budget = frameBudget;
        budget.stats = stats;
        int uploads = frameUploads;
        if (Double.isNaN(frameTime)) frameTime = cache.time(event.getPartialTick().getGameTimeDeltaPartialTick(level.tickRateManager().runsNormally()));
        double time = frameTime;
        var dynamic = new ArrayList<ClientTrackCache.TurnoutEntry>();
        var nativeDynamic = new ArrayList<ClientTrackCache.AssemblyEntry>();
        var camera = event.getCamera().getPosition();
        Matrix4f modelView = new Matrix4f();
        try (var irisMetrics = iris ? IrisRenderStats.begin(IrisRenderStats.TRACK, shadow) : null;
             var irisScope = iris ? new IrisDrawScope() : null;
             var state = iris ? null : new TrackRenderState(stats)) {
            long irisPrepareStart = IrisRenderStats.clock();
            // Script failures are recorded during a build; rebuild those meshes at a point outside cache iteration.
            cache.flushScriptFailures();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            for (var chunkEntry : cache.chunks.entrySet()) {
                long chunk = chunkEntry.getKey();
                int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                if (!level.hasChunk(x, z)) continue;
                if (stats != null) stats.chunk();
                for (UUID id : chunkEntry.getValue()) {
                    var entry = cache.entries.get(id);
                    TrackRenderMesh mesh = entry.meshes.get(chunk);
                    if (mesh == null) {
                        if (!event.getFrustum().isVisible(entry.broadBounds(x, z)) || !budget.available()) {
                            if (stats != null) stats.candidate();
                            continue;
                        }
                        var model = ClientRailModelManager.INSTANCE.model(entry.data.modelId());
                        if (entry.staticSnapshot == null && model != null
                                && model.script() != null && !model.script().failed()) {
                            long start = System.nanoTime();
                            try { prepareTrackScript(entry, model); }
                            finally { budget.finished(start, entry.data.modelId(), id, x, z, "railStaticCapture"); }
                            if (!budget.available()) continue;
                        }
                        long buildStart = System.nanoTime();
                        try {
                            mesh = build(entry, model, id, x, z);
                            entry.meshes.put(chunk, mesh);
                        } finally { budget.finished(buildStart, entry.data.modelId(), id, x, z, "trackMesh"); }
                    }
                    uploads = draw(stats, queue, event, level, camera, modelView, uploads, mesh,
                            TrackRenderStats.STRUCTURE_TRACK);
                }
            }
            for (var entry : cache.assemblies.values()) {
                if (!entry.rendersModel()) continue;
                boolean simple = entry.data.definition().type() == TrackAssemblyType.SIMPLE_TURNOUT;
                var model = ClientRailModelManager.INSTANCE.model(entry.data.modelId());
                if (com.unrealtrainmod.track.model.NativeTurnoutCatalog.contains(entry.data.modelId()) && !entry.nativeFailed) {
                    if (!event.getFrustum().isVisible(entry.broadBounds())) continue;
                    if (entry.nativeState == null) {
                        if (!budget.available()) continue;
                        long start = System.nanoTime();
                        try {
                            if (model == null) throw new IllegalArgumentException("native model unavailable");
                            var layout = com.unrealtrainmod.track.mesh.NativeTurnoutLayout.build(entry.data.geometry(), entry.data.gauge());
                            entry.nativeState = new NativeTurnoutRenderState(layout, model);
                            if (stats != null) stats.nativeLayout(layout.samples(), layout.parts().size());
                        } catch (IllegalArgumentException exception) {
                            entry.nativeFailed = true;
                            LOGGER.warn("Native turnout {} uses simplified fallback: {}", entry.data.id(), exception.getMessage());
                        } finally { budget.finished(start, entry.data.modelId(), entry.data.id(), 0, 0, "nativeLayout"); }
                    }
                    if (entry.nativeState != null) {
                        var nativeState = entry.nativeState;
                        nativeState.movement(entry.animations.get("main").position(time));
                        nativeDynamic.add(entry);
                        for (long chunk : entry.chunks) {
                            if (!level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue;
                            var group = entry.meshes.get(chunk);
                            if (group == null) {
                                if (!budget.available()) continue;
                                long start = System.nanoTime();
                                try {
                                    group = List.of(nativeState.build(chunk));
                                    entry.meshes.put(chunk, group);
                                } finally { budget.finished(start, entry.data.modelId(), entry.data.id(),
                                        ChunkPos.getX(chunk), ChunkPos.getZ(chunk), "nativeFixedMesh"); }
                            }
                            uploads = drawAll(stats, queue, event, level, camera, modelView, uploads, group);
                        }
                        continue;
                    }
                }
                if (entry.nativeFailed && stats != null) stats.nativeFallback("layout_or_resource");
                boolean scripted = entry.script != null && model != null && model.scripted() && !entry.scriptFailed;
                boolean inView = event.getFrustum().isVisible(entry.broadBounds());
                if (scripted && !entry.prepared() && !shadow && inView
                        && entry.scriptPrepareFrame != frame && budget.available()) {
                    entry.scriptPrepareFrame = frame;
                    var turnout = entry.script;
                    long start = System.nanoTime();
                    var anchor = turnout.view.origin();
                    String phase = turnout.assemblyScript == null ? "turnoutScriptInit"
                            : turnout.staticSnapshot == null ? "turnoutStaticCapture" : "turnoutDynamicCapture";
                    try { prepareAssemblyScript(turnout, model, time); }
                    finally { budget.finished(start, entry.data.modelId(), entry.data.id(),
                            (int)Math.floor(anchor.x() / 16), (int)Math.floor(anchor.z() / 16), phase + "/" + turnout.view.control()); }
                }
                if (scripted && !entry.scriptFailed && entry.prepared() && inView) {
                    boolean complete = prepareTurnoutMeshes(entry, budget, level);
                    if (complete && !shadow && !entry.scriptActive) {
                        boolean ready = turnoutMeshesReady(entry, level);
                        if (!iris) {
                            var turnout = entry.script;
                            uploads = turnout.parts.prepareSources(turnout.currentSnapshot, uploads, stats);
                            ready &= turnout.parts.sourcesReady(turnout.currentSnapshot);
                        }
                        if (ready) entry.activateScripts();
                    }
                }
                if (entry.scriptActive) {
                    dynamic.add(entry.script);
                    if (!inView) continue;
                    for (long chunk : entry.chunks) {
                        if (!level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue;
                        if (!entry.script.meshes.containsKey(chunk)) continue;
                        if (stats != null) stats.chunk();
                        uploads = draw(stats, queue, event, level, camera, modelView, uploads,
                                entry.script.meshes.get(chunk), TrackRenderStats.STRUCTURE_ASSEMBLY);
                    }
                    continue;
                }
                if (!inView) continue;
                for (long chunk : entry.chunks) {
                    int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                    if (!level.hasChunk(x, z)) continue;
                    if (stats != null) stats.chunk();
                    if (simple) {
                        for (int pass = 0; pass < 2; pass++) {
                            boolean tongue = pass == 1;
                            var meshes = tongue ? entry.tongues : entry.meshes;
                            var group = meshes.get(chunk);
                            if (group == null) {
                                if (!budget.available()) {
                                    if (stats != null) stats.candidate();
                                    continue;
                                }
                                long buildStart = System.nanoTime();
                                TrackRenderMesh mesh;
                                try {
                                    mesh = tongue ? new TrackMorphMesh(
                                            TrackMeshBuilder.buildTurnout(entry.data, x, z, true, 0),
                                            TrackMeshBuilder.buildTurnout(entry.data, x, z, true, 1))
                                            : new TrackGpuMesh(TrackMeshBuilder.buildTurnout(entry.data, x, z, false));
                                } catch (IllegalArgumentException exception) {
                                    LOGGER.warn("Cannot build turnout {} in chunk {}, {}: {}", entry.data.id(), x, z, exception.getMessage());
                                    mesh = new TrackGpuMesh(TrackMeshData.EMPTY);
                                } finally {
                                    budget.finished(buildStart, entry.data.modelId(), entry.data.id(), x, z,
                                            tongue ? "turnoutTongueMesh" : "turnoutFixedMesh");
                                }
                                group = List.of(mesh); meshes.put(chunk, group);
                            }
                            if (tongue) for (var mesh : group) {
                                if (mesh instanceof TrackMorphMesh moving) moving.movement(entry.animations.get("main").position(time));
                            }
                            uploads = drawAll(stats, queue, event, level, camera, modelView, uploads, group);
                        }
                    } else {
                        var group = entry.meshes.get(chunk);
                        if (group == null) {
                            if (!budget.available()) {
                                if (stats != null) stats.candidate();
                                continue;
                            }
                            group = new ArrayList<TrackRenderMesh>();
                            entry.meshes.put(chunk, group);
                        }
                        while (group.size() < entry.geometries.size() && budget.available()) {
                            var geometry = entry.geometries.get(group.size());
                            long buildStart = System.nanoTime();
                            try {
                                group.add(build(geometry, entry.data.gauge(), entry.data.modelId(), model, entry.data.id(),
                                        x, z, !entry.missingReported));
                                entry.missingReported = true;
                            } finally { budget.finished(buildStart, entry.data.modelId(), entry.data.id(), x, z, "assemblyMesh"); }
                        }
                        uploads = drawAll(stats, queue, event, level, camera, modelView, uploads, group);
                    }
                }
            }
            // Iris fixed-part sources join the same budgeted page build as the other static geometry.
            if (iris) {
                for (var entry : nativeDynamic) {
                    var nativeState = entry.nativeState;
                    uploads = nativeState.parts.drawIris(nativeState.snapshot, null, 1, entry.chunks,
                            event, level, queue, uploads, stats, budget, shadow);
                }
                for (var entry : dynamic) {
                    uploads = entry.parts.drawIris(entry.currentSnapshot, entry.previousSnapshot, entry.snapshotFraction(time),
                            entry.chunks, event, level, queue, uploads, stats, budget, shadow);
                }
            }
            // Build and upload replacement pages before resolving any GPU range references.
            uploads = TrackStaticBuffers.WORLD.prepare(level, budget, uploads, stats);
            long resolveStart = stats == null ? 0 : System.nanoTime();
            queue.resolve(TrackStaticBuffers.WORLD, stats);
            if (stats != null) stats.grouped(System.nanoTime() - resolveStart);
            TrackStaticBuffers.WORLD.collectStats(stats);
            if (!iris) queue.draw(state, event.getProjectionMatrix(), stats);
            if (!iris) for (var entry : nativeDynamic) {
                var nativeState = entry.nativeState;
                uploads = nativeState.parts.draw(state, nativeState.snapshot, null, 1, entry.chunks,
                        event, level, uploads, stats);
            }
            // Shared track scope also owns rigid shaders; each renderer releases its instance attributes.
            if (!iris) for (var entry : dynamic) {
                uploads = entry.parts.draw(state, entry.currentSnapshot, entry.previousSnapshot, entry.snapshotFraction(time),
                        entry.chunks, event, level, uploads, stats);
            }
            if (iris) {
                IrisRenderStats.prepare(irisPrepareStart);
                irisScope.beginTrackBatch();
                queue.draw(null, event.getProjectionMatrix(), stats);
            }
            cache.collectStats(stats, time);
        } finally {
            queue.clear();
            frameUploads = uploads;
            if (stats != null) {
                stats.stage(System.nanoTime() - stageStart);
                stats.endFrame();
            }
        }
        if (stats != null) stats.logIfDue();
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
        for (long chunk : entry.chunks) {
            int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
            if (!level.hasChunk(x, z) || turnout.meshes.containsKey(chunk)) continue;
            if (!budget.available()) return false;
            long start = System.nanoTime();
            try {
                turnout.meshes.put(chunk, new RailTrackGpuMesh(turnout.staticSnapshot.build(x, z)));
            } catch (RuntimeException | LinkageError exception) {
                entry.failScripts(turnout, "static replay", exception);
                return false;
            } finally {
                budget.finished(start, entry.data.modelId(), entry.data.id(), x, z, "turnoutStaticReplay/main");
            }
        }
        return true;
    }

    private static boolean turnoutMeshesReady(ClientTrackCache.AssemblyEntry entry, ClientLevel level) {
        boolean ready = true;
        for (long chunk : entry.chunks) {
            if (!level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue;
            var mesh = entry.script.meshes.get(chunk);
            if (mesh.isEmpty()) continue;
            TrackStaticBuffers.WORLD.request(mesh, TrackRenderStats.STRUCTURE_ASSEMBLY);
            ready &= TrackStaticBuffers.WORLD.ready(mesh);
        }
        return ready;
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

    private static TrackRenderMesh build(ClientTrackCache.Entry entry, ClientRailModelManager.Model model, UUID id, int x, int z) {
        if (entry.staticSnapshot != null && model != null && model.script() != null && !model.script().failed()) {
            try {
                return new RailTrackGpuMesh(entry.staticSnapshot.build(x, z));
            } catch (RuntimeException | LinkageError exception) {
                model.script().close();
                entry.staticSnapshot = null;
                ClientTrackCache.INSTANCE.invalidateScriptedModel(model);
                LOGGER.warn("Cannot replay rail script for track {} in chunk {}, {}; using the full static model",
                        id, x, z, exception);
            }
        }
        var mesh = build(entry.geometry, entry.data.gauge(), entry.data.modelId(), model, id, x, z, !entry.missingReported);
        entry.missingReported = true;
        return mesh;
    }

    private static void prepareTrackScript(ClientTrackCache.Entry entry, ClientRailModelManager.Model model) {
        var start = entry.geometry.sample(0).position();
        var startPoint = BlockPos.containing(start.x(), start.y() + 0.0625, start.z());
        entry.staticSnapshot = model.script().captureStatic(entry.geometry, startPoint,
                model.scaleForGauge(entry.data.gauge()), model.period());
        if (entry.staticSnapshot == null) {
            ClientTrackCache.INSTANCE.invalidateScriptedModel(model);
            LOGGER.warn("Rail script for {} failed; using the full static model from now on", entry.data.modelId());
        }
    }

    private static TrackRenderMesh build(TrackGeometry geometry, double gauge, String modelId, ClientRailModelManager.Model model, UUID id,
                                         int x, int z, boolean reportMissing) {
        if (modelId != null) {
            if (model != null) {
                try {
                    double scale = model.scaleForGauge(gauge);
                    // No script, a failed script or a dynamic assembly: flat material merge with every part.
                    return new RailTrackGpuMesh(RailTrackMeshBuilder.build(geometry, staticSources(model), x, z,
                            scale, model.period(), RailTrackMeshBuilder.Filter.ALL));
                } catch (IllegalArgumentException exception) {
                    LOGGER.warn("Cannot build rail {} using {} in chunk {}, {}: {}; using native appearance",
                            id, modelId, x, z, exception.getMessage());
                }
            } else if (reportMissing) {
                LOGGER.warn("Rail model {} is missing or unavailable for track {}; using native appearance", modelId, id);
            }
        }
        try {
            return new TrackGpuMesh(TrackMeshBuilder.build(geometry, x, z));
        } catch (IllegalArgumentException exception) {
            // Retain an empty batch so invalid/oversized input does not retry on every frame.
            LOGGER.warn("Cannot build track {} in chunk {}, {}: {}", id, x, z, exception.getMessage());
            return new TrackGpuMesh(TrackMeshData.EMPTY);
        }
    }

    /** Flat material merge used when no filter runs or after the script fell back to the full model. */
    private static List<RailTrackMeshBuilder.Source> staticSources(ClientRailModelManager.Model model) {
        var sources = new ArrayList<RailTrackMeshBuilder.Source>();
        for (var batch : model.batches()) {
            sources.add(new RailTrackMeshBuilder.Source("", batch.texture(), batch.doubleSided(), batch.mesh()));
        }
        return sources;
    }
}
