/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.unrealtrainmod.track.mesh.TrackMeshBuilder;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
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
        var previousShader = RenderSystem.getShader();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        int builds = BUILDS_PER_FRAME, uploads = UPLOADS_PER_FRAME;
        var camera = event.getCamera().getPosition();
        Matrix4f modelView = new Matrix4f();
        try {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            for (var chunkEntry : cache.chunks.entrySet()) {
                long chunk = chunkEntry.getKey();
                int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                if (!level.hasChunk(x, z)) continue;
                for (UUID id : chunkEntry.getValue()) {
                    var entry = cache.entries.get(id);
                    TrackRenderMesh mesh = entry.meshes.get(chunk);
                    if (mesh == null) {
                        if (builds == 0 || !event.getFrustum().isVisible(entry.broadBounds(x, z))) continue;
                        mesh = build(entry, id, x, z);
                        entry.meshes.put(chunk, mesh);
                        builds--;
                    }
                    if (mesh.isEmpty() || !event.getFrustum().isVisible(mesh.bounds())) continue;
                    // Uploaded meshes also check for local light changes; old buffers remain drawable during updates.
                    uploads -= mesh.upload(level, uploads);
                    if (!mesh.isUploaded()) continue;
                    var origin = mesh.origin();
                    modelView.set(event.getModelViewMatrix()).translate((float)(origin.x() - camera.x),
                            (float)(origin.y() - camera.y), (float)(origin.z() - camera.z));
                    mesh.draw(modelView, event.getProjectionMatrix());
                }
            }
            for (var entry : cache.assemblies.values()) {
                if (!event.getFrustum().isVisible(entry.broadBounds())) continue;
                for (long chunk : entry.chunks) {
                    int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                    if (!level.hasChunk(x, z)) continue;
                    for (int pass = 0; pass < 2; pass++) {
                        boolean tongue = pass == 1;
                        var meshes = tongue ? entry.tongues : entry.meshes;
                        var mesh = meshes.get(chunk);
                        if (mesh == null) {
                            if (builds == 0) continue;
                            try {
                                if (entry.data.definition() == TrackAssemblyDefinition.CUSTOM) {
                                    var geometry = entry.data.geometry().paths().get(tongue ? "diverging" : "straight").getFirst();
                                    mesh = build(geometry, entry.data.gauge(), entry.data.modelId(), entry.data.id(), x, z, !entry.missingReported);
                                    entry.missingReported = true;
                                } else mesh = new TrackGpuMesh(TrackMeshBuilder.buildTurnout(entry.data, x, z, tongue));
                            } catch (IllegalArgumentException exception) {
                                LOGGER.warn("Cannot build turnout {} in chunk {}, {}: {}", entry.data.id(), x, z, exception.getMessage());
                                mesh = new TrackGpuMesh(TrackMeshData.EMPTY);
                            }
                            meshes.put(chunk, mesh); builds--;
                        }
                        if (mesh.isEmpty() || !event.getFrustum().isVisible(mesh.bounds())) continue;
                        uploads -= mesh.upload(level, uploads);
                        if (!mesh.isUploaded()) continue;
                        var origin = mesh.origin();
                        modelView.set(event.getModelViewMatrix()).translate((float)(origin.x() - camera.x),
                                (float)(origin.y() - camera.y), (float)(origin.z() - camera.z));
                        mesh.draw(modelView, event.getProjectionMatrix());
                    }
                }
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }

    private static TrackRenderMesh build(ClientTrackCache.Entry entry, UUID id, int x, int z) {
        var mesh = build(entry.geometry, entry.data.gauge(), entry.data.modelId(), id, x, z, !entry.missingReported);
        entry.missingReported = true;
        return mesh;
    }

    private static TrackRenderMesh build(TrackGeometry geometry, double gauge, String modelId, UUID id,
                                         int x, int z, boolean reportMissing) {
        if (modelId != null) {
            var model = ClientRailModelManager.INSTANCE.model(modelId);
            if (model != null) {
                try {
                    return new RailTrackGpuMesh(RailTrackMeshBuilder.build(geometry,
                            model.batches().stream().map(ClientRailModelManager.Batch::mesh).toList(), x, z,
                            model.scaleForGauge(gauge), model.period()), model, false);
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
}
