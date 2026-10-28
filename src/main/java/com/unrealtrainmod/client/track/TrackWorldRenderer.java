/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.mesh.TrackMeshBuilder;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Independent opaque pass; vertices are generated/uploaded once and reused on ordinary frames. */
public final class TrackWorldRenderer {
    private static final int BUILDS_PER_FRAME = 4;
    private static final int UPLOADS_PER_FRAME = 4;

    private TrackWorldRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        ClientTrackCache cache = ClientTrackCache.INSTANCE;
        var level = Minecraft.getInstance().level;
        if (level == null || cache.world() != level || cache.entries.isEmpty()) return;
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
                    if (!mesh.isUploaded()) {
                        if (uploads == 0) continue;
                        uploads -= mesh.upload(uploads);
                        if (!mesh.isUploaded()) continue;
                    }
                    var origin = mesh.origin();
                    modelView.set(event.getModelViewMatrix()).translate((float)(origin.x() - camera.x),
                            (float)(origin.y() - camera.y), (float)(origin.z() - camera.z));
                    mesh.draw(modelView, event.getProjectionMatrix());
                }
            }
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }

    private static TrackRenderMesh build(ClientTrackCache.Entry entry, UUID id, int x, int z) {
        String modelId = entry.data.modelId();
        if (modelId != null) {
            var model = ClientRailModelManager.INSTANCE.model(modelId);
            if (model != null) {
                try {
                    return new RailTrackGpuMesh(RailTrackMeshBuilder.build(entry.geometry,
                            model.batches().stream().map(ClientRailModelManager.Batch::mesh).toList(), x, z), model);
                } catch (IllegalArgumentException exception) {
                    UnrealTrainMod.LOGGER.warn("Cannot build RTM rail {} using {} in chunk {}, {}: {}; using native appearance",
                            id, modelId, x, z, exception.getMessage());
                }
            } else if (!entry.missingReported) {
                entry.missingReported = true;
                UnrealTrainMod.LOGGER.warn("RTM rail model {} is missing or unavailable for track {}; using native appearance", modelId, id);
            }
        }
        try {
            return new TrackGpuMesh(TrackMeshBuilder.build(entry.geometry, x, z));
        } catch (IllegalArgumentException exception) {
            // Retain an empty batch so invalid/oversized input does not retry on every frame.
            UnrealTrainMod.LOGGER.warn("Cannot build track {} in chunk {}, {}: {}", id, x, z, exception.getMessage());
            return new TrackGpuMesh(TrackMeshData.EMPTY);
        }
    }
}
