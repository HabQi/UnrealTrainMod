/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.track.mesh.TrackMeshBuilder;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
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
        var previousShader = stateCache ? null : RenderSystem.getShader();
        float[] previousColor = stateCache ? null : RenderSystem.getShaderColor().clone();
        int builds = BUILDS_PER_FRAME, uploads = UPLOADS_PER_FRAME;
        var camera = event.getCamera().getPosition();
        Matrix4f modelView = new Matrix4f();
        try (var state = stateCache ? new TrackRenderState(stats) : null) {
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
                if (!event.getFrustum().isVisible(entry.broadBounds())) continue;
                boolean simplified = entry.data.definition().direction() != null;
                for (long chunk : entry.chunks) {
                    int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                    if (!level.hasChunk(x, z)) continue;
                    if (stats != null) stats.chunk();
                    if (simplified) {
                        // Built-in turnouts keep their moving tongue meshes on a dedicated two-pass path.
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
                                    mesh = new TrackGpuMesh(TrackMeshBuilder.buildTurnout(entry.data, x, z, tongue));
                                } catch (IllegalArgumentException exception) {
                                    LOGGER.warn("Cannot build turnout {} in chunk {}, {}: {}", entry.data.id(), x, z, exception.getMessage());
                                    mesh = new TrackGpuMesh(TrackMeshData.EMPTY);
                                }
                                group = List.of(mesh); meshes.put(chunk, group); builds--;
                                if (stats != null) stats.built(System.nanoTime() - buildStart);
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
                        // The cached prefix survives budget exhaustion and is drawn while later segments wait.
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
        } finally {
            if (!stateCache) {
                VertexBuffer.unbind();
                RenderSystem.setShader(() -> previousShader);
                RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            }
            if (stats != null) {
                stats.stage(System.nanoTime() - stageStart);
                stats.endFrame();
            }
        }
        if (stats != null) stats.logIfDue();
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
                        try {
                            var start = geometry.sample(0).position();
                            var startPoint = BlockPos.containing(start.x(), start.y(), start.z());
                            var filtered = RailTrackMeshBuilder.build(geometry, partSources(model), x, z,
                                    scale, model.period(), new RailScriptFilter(model, startPoint));
                            return new RailTrackGpuMesh(filtered, false);
                        } catch (RailScriptFilter.Failure failure) {
                            LOGGER.warn("Rail script for {} failed; using the full static model from now on", modelId);
                        }
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

    /** Authored object groups, so the script filter can still name individual parts. */
    private static List<RailTrackMeshBuilder.Source> partSources(ClientRailModelManager.Model model) {
        var sources = new ArrayList<RailTrackMeshBuilder.Source>();
        for (var part : model.parts()) {
            for (var batch : part.batches()) {
                sources.add(new RailTrackMeshBuilder.Source(part.name(), batch.texture(),
                        batch.doubleSided(), batch.mesh()));
            }
        }
        return sources;
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
