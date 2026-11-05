/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.track.mesh.TrackMeshBuilder;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Independent opaque pass; static geometry is reused, with budgeted light updates. */
public final class TrackWorldRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrackWorldRenderer.class);
    private static final int BUILDS_PER_FRAME = 4;
    private static final int UPLOADS_PER_FRAME = 4;

    private TrackWorldRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        ClientTrackCache cache = ClientTrackCache.INSTANCE;
        var level = Minecraft.getInstance().level;
        if (level == null || cache.world() != level || (cache.entries.isEmpty() && cache.assemblies.isEmpty())) return;
        RenderSystem.assertOnRenderThread();
        // All three paths share cached geometry, visibility decisions, build and upload budgets.
        boolean stateCache = ClientConfig.USE_TRACK_RENDER_STATE_CACHE.getAsBoolean();
        boolean groupMaterials = stateCache && ClientConfig.GROUP_TRACK_DRAWS_BY_MATERIAL.getAsBoolean();
        TrackRenderStats stats = TrackRenderStats.begin(level, "world",
                groupMaterials ? "materialGroups" : stateCache ? "stateCache" : "legacy");
        long stageStart = stats == null ? 0L : System.nanoTime();
        TrackDrawQueue queue = groupMaterials ? new TrackDrawQueue() : null;
        int builds = BUILDS_PER_FRAME, uploads = UPLOADS_PER_FRAME;
        double time = cache.time(event.getPartialTick().getGameTimeDeltaPartialTick(level.tickRateManager().runsNormally()));
        var dynamic = new ArrayList<ClientTrackCache.AssemblyEntry>();
        var camera = event.getCamera().getPosition();
        Matrix4f modelView = new Matrix4f();
        try (var protection = stateCache ? null : new TrackRenderState(null);
             var state = stateCache ? new TrackRenderState(stats) : null) {
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
                        if (builds == 0 || !event.getFrustum().isVisible(entry.broadBounds(x, z))) {
                            if (stats != null) stats.candidate();
                            continue;
                        }
                        long buildStart = stats == null ? 0L : System.nanoTime();
                        mesh = build(entry, id, x, z);
                        entry.meshes.put(chunk, mesh);
                        builds--;
                        if (stats != null) stats.built(System.nanoTime() - buildStart);
                    }
                    uploads = draw(stats, state, queue, event, level, camera, modelView, uploads, mesh,
                            TrackRenderStats.STRUCTURE_TRACK);
                }
            }
            for (var entry : cache.assemblies.values()) {
                if (entry.scriptFailed) entry.clearScriptMeshes();
                boolean simple = entry.data.definition().type() == TrackAssemblyType.SIMPLE_TURNOUT;
                var model = ClientRailModelManager.INSTANCE.model(entry.data.modelId());
                boolean scriptedCustom = entry.data.definition() == TrackAssemblyDefinition.CUSTOM
                        && model != null && model.scripted();
                boolean inView = event.getFrustum().isVisible(entry.broadBounds());
                if (scriptedCustom && !entry.scriptAttempted && inView && builds > 0) {
                    long start = stats == null ? 0 : System.nanoTime();
                    prepareAssemblyScript(entry, model, time);
                    builds--;
                    if (stats != null) stats.built(System.nanoTime() - start);
                }
                if (entry.scriptFailed) entry.clearScriptMeshes();
                if (scriptedCustom && !entry.scriptFailed) {
                    if (entry.parts != null) dynamic.add(entry);
                    if (!inView || entry.staticSnapshot == null) continue;
                } else if (!inView) continue;
                for (long chunk : entry.chunks) {
                    int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                    if (!level.hasChunk(x, z)) continue;
                    if (stats != null) stats.chunk();
                    if (scriptedCustom && !entry.scriptFailed) {
                        var group = entry.meshes.get(chunk);
                        if (group == null && builds > 0) {
                            long start = stats == null ? 0 : System.nanoTime();
                            try {
                                group = List.of(new RailTrackGpuMesh(entry.staticSnapshot.build(x, z), false));
                            } catch (RuntimeException | LinkageError exception) {
                                entry.scriptFailed = true;
                                entry.assemblyScript.close();
                                dynamic.remove(entry);
                                LOGGER.warn("Cannot build scripted turnout {} in chunk {}, {}; using native appearance",
                                        entry.data.id(), x, z, exception);
                                break;
                            } finally {
                                builds--;
                                if (stats != null) stats.built(System.nanoTime() - start);
                            }
                            entry.meshes.put(chunk, group);
                        }
                        if (group != null) uploads = drawAll(stats, state, queue, event, level, camera, modelView, uploads, group);
                        continue;
                    }
                    if (simple) {
                        for (int pass = 0; pass < 2; pass++) {
                            boolean tongue = pass == 1;
                            var meshes = tongue ? entry.tongues : entry.meshes;
                            var group = meshes.get(chunk);
                            if (group == null) {
                                if (builds == 0) {
                                    if (stats != null) stats.candidate();
                                    continue;
                                }
                                long buildStart = stats == null ? 0L : System.nanoTime();
                                TrackRenderMesh mesh;
                                try {
                                    mesh = tongue ? new TrackMorphMesh(
                                            TrackMeshBuilder.buildTurnout(entry.data, x, z, true, 0),
                                            TrackMeshBuilder.buildTurnout(entry.data, x, z, true, 1))
                                            : new TrackGpuMesh(TrackMeshBuilder.buildTurnout(entry.data, x, z, false));
                                } catch (IllegalArgumentException exception) {
                                    LOGGER.warn("Cannot build turnout {} in chunk {}, {}: {}", entry.data.id(), x, z, exception.getMessage());
                                    mesh = new TrackGpuMesh(TrackMeshData.EMPTY);
                                }
                                group = List.of(mesh); meshes.put(chunk, group); builds--;
                                if (stats != null) stats.built(System.nanoTime() - buildStart);
                            }
                            if (tongue) for (var mesh : group) {
                                if (mesh instanceof TrackMorphMesh moving) moving.movement(entry.animation.position(time));
                            }
                            uploads = drawAll(stats, state, queue, event, level, camera, modelView, uploads, group);
                        }
                    } else {
                        var group = entry.meshes.get(chunk);
                        if (group == null) {
                            if (builds == 0) {
                                if (stats != null) stats.candidate();
                                continue;
                            }
                            group = new ArrayList<TrackRenderMesh>();
                            entry.meshes.put(chunk, group);
                        }
                        while (builds > 0 && group.size() < entry.geometries.size()) {
                            var geometry = entry.geometries.get(group.size());
                            long buildStart = stats == null ? 0L : System.nanoTime();
                            group.add(build(geometry, entry.data.gauge(), entry.data.modelId(), entry.data.id(),
                                    x, z, !entry.missingReported, false));
                            entry.missingReported = true;
                            builds--;
                            if (stats != null) stats.built(System.nanoTime() - buildStart);
                        }
                        uploads = drawAll(stats, state, queue, event, level, camera, modelView, uploads, group);
                    }
                }
            }
            // Uploads may change GL bindings. Begin grouped submission only after all uploads are done.
            if (queue != null) queue.draw(state, event.getProjectionMatrix(), stats);
            // Shared track scope also owns rigid shaders; each renderer releases its instance attributes.
            for (var entry : dynamic) {
                uploads = state != null
                        ? entry.parts.draw(state, entry, event, level, time, true, groupMaterials, uploads, stats)
                        : entry.parts.draw(entry, event, level, time, false, groupMaterials, uploads, stats);
            }
            cache.collectStats(stats, time);
        } finally {
            if (stats != null) {
                stats.stage(System.nanoTime() - stageStart);
                stats.endFrame();
            }
        }
        if (stats != null) stats.logIfDue();
    }

    private static void prepareAssemblyScript(ClientTrackCache.AssemblyEntry entry, ClientRailModelManager.Model model, double time) {
        entry.scriptAttempted = true;
        if (model.script() == null) {
            entry.scriptFailed = true;
            LOGGER.warn("Turnout {} has an unavailable rail script; using native appearance", entry.data.id());
            return;
        }
        try {
            entry.assemblyScript = model.script().newAssembly(entry.data);
            entry.staticSnapshot = entry.assemblyScript.captureAssemblyStatic(entry.animation.position(time),
                    model.scaleForGauge(entry.data.gauge()), model.period());
            if (entry.staticSnapshot == null) { entry.scriptFailed = true; return; }
            var a = entry.data.geometry().ports().get("A").position();
            var anchor = BlockPos.containing(a.x(), a.y() + 0.0625, a.z());
            entry.parts = new RailPartRenderer(new TrackVector(
                    anchor.getX(), anchor.getY(), anchor.getZ()), Math.floor(a.y() / 16) * 16);
            entry.captureDynamic(time);
        } catch (IOException | RuntimeException | LinkageError exception) {
            entry.scriptFailed = true;
            if (entry.assemblyScript != null) entry.assemblyScript.close();
            LOGGER.warn("Cannot initialise rail script for turnout {}; using native appearance: {}",
                    entry.data.id(), exception.getMessage());
            LOGGER.debug("Turnout script initialisation failure", exception);
        }
    }

    private static int drawAll(TrackRenderStats stats, TrackRenderState state, TrackDrawQueue queue, RenderLevelStageEvent event,
                               ClientLevel level, Vec3 camera, Matrix4f modelView, int uploads,
                               List<TrackRenderMesh> group) {
        for (var mesh : group) {
            uploads = draw(stats, state, queue, event, level, camera, modelView, uploads, mesh,
                    TrackRenderStats.STRUCTURE_ASSEMBLY);
        }
        return uploads;
    }

    private static int draw(TrackRenderStats stats, TrackRenderState state, TrackDrawQueue queue, RenderLevelStageEvent event,
                            ClientLevel level, Vec3 camera, Matrix4f modelView, int uploads,
                            TrackRenderMesh mesh, int structure) {
        if (stats != null) stats.candidate();
        if (mesh.isEmpty()) {
            if (stats != null) stats.empty();
            return uploads;
        }
        if (!event.getFrustum().isVisible(mesh.bounds())) return uploads;
        if (stats != null) stats.visible();
        // Uploaded meshes also check for local light changes; old buffers remain drawable during updates.
        uploads -= mesh.upload(level, uploads, stats);
        if (!mesh.isUploaded()) {
            if (stats != null) stats.pending();
            return uploads;
        }
        var origin = mesh.origin();
        modelView.set(event.getModelViewMatrix()).translate((float)(origin.x() - camera.x),
                (float)(origin.y() - camera.y), (float)(origin.z() - camera.z));
        long drawStart = stats == null ? 0L : System.nanoTime();
        if (queue != null) {
            mesh.enqueue(queue, new Matrix4f(modelView), structure);
            if (stats != null) stats.grouped(System.nanoTime() - drawStart);
            return uploads;
        }
        if (state != null) mesh.draw(state, modelView, event.getProjectionMatrix());
        else mesh.draw(modelView, event.getProjectionMatrix(), stats);
        if (stats != null) {
            stats.drawn(mesh.source(), structure, mesh.drawCalls(), mesh.drawVertices(), mesh.drawTriangles(),
                    mesh.storedVertices(), System.nanoTime() - drawStart);
        }
        return uploads;
    }

    private static TrackRenderMesh build(ClientTrackCache.Entry entry, UUID id, int x, int z) {
        var mesh = build(entry.geometry, entry.data.gauge(), entry.data.modelId(), id, x, z, !entry.missingReported, true);
        entry.missingReported = true;
        return mesh;
    }

    private static TrackRenderMesh build(TrackGeometry geometry, double gauge, String modelId, UUID id,
                                         int x, int z, boolean reportMissing, boolean filterScript) {
        if (modelId != null) {
            var model = ClientRailModelManager.INSTANCE.model(modelId);
            if (model != null) {
                try {
                    double scale = model.scaleForGauge(gauge);
                    var script = filterScript ? model.script() : null;
                    if (script != null && !script.failed()) {
                        var start = geometry.sample(0).position();
                        var startPoint = BlockPos.containing(start.x(), start.y() + 0.0625, start.z());
                        var scripted = script.buildStatic(geometry, startPoint, x, z, scale, model.period(), false);
                        if (scripted != null) return new RailTrackGpuMesh(scripted, false);
                        ClientTrackCache.INSTANCE.invalidateScriptedModel(model);
                        LOGGER.warn("Rail script for {} failed; using the full static model from now on", modelId);
                    }
                    // No script, a failed script or a dynamic assembly: flat material merge with every part.
                    return new RailTrackGpuMesh(RailTrackMeshBuilder.build(geometry, staticSources(model), x, z,
                            scale, model.period(), RailTrackMeshBuilder.Filter.ALL), false);
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
