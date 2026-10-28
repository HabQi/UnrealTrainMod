/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** GPU lifetime is separate from the CPU geometry, so resource reloads need no server resync. */
final class TrackGpuMesh implements TrackRenderMesh {
    final TrackMeshData data;
    final AABB bounds;
    private VertexBuffer buffer;
    private final TrackLighting lighting;
    private boolean lightDirty = true;

    TrackGpuMesh(TrackMeshData data) {
        this.data = data;
        var box = data.bounds();
        bounds = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).inflate(0.001);
        lighting = new TrackLighting(data.origin());
    }

    @Override public boolean isUploaded() { return buffer != null; }
    @Override public boolean isEmpty() { return data.vertexCount() == 0; }
    @Override public AABB bounds() { return bounds; }
    @Override public TrackVector origin() { return data.origin(); }

    @Override
    public int upload(ClientLevel level, int budget) {
        if (!lightDirty && lighting.refresh(level)) lightDirty = true;
        if (!lightDirty || budget == 0) return 0;
        upload(level);
        lightDirty = false;
        return 1;
    }

    private void upload(ClientLevel level) {
        if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try (var bytes = new ByteBufferBuilder(Math.multiplyExact(data.vertexCount(), DefaultVertexFormat.POSITION_COLOR_LIGHTMAP.getVertexSize()))) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_LIGHTMAP);
            for (int first = 0; first < data.vertexCount(); first += 4) {
                double ax = data.x(first + 1) - data.x(first), ay = data.y(first + 1) - data.y(first), az = data.z(first + 1) - data.z(first);
                double bx = data.x(first + 2) - data.x(first), by = data.y(first + 2) - data.y(first), bz = data.z(first + 2) - data.z(first);
                double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 0) { nx /= length; ny /= length; nz /= length; }
                for (int i = first; i < first + 4; i++) {
                    int light = lighting.sample(level, data.x(i), data.y(i), data.z(i), nx, ny, nz);
                    builder.addVertex(data.x(i), data.y(i), data.z(i)).setColor(data.color(i)).setLight(light);
                }
            }
            buffer.bind();
            // VertexBuffer.upload owns and closes the temporary Minecraft MeshData.
            buffer.upload(builder.buildOrThrow());
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        } finally {
            VertexBuffer.unbind();
        }
    }

    @Override
    public void draw(Matrix4f modelView, Matrix4f projection) {
        TrackRenderTypes.OPAQUE.setupRenderState();
        try {
            buffer.bind();
            buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
        } finally { TrackRenderTypes.OPAQUE.clearRenderState(); }
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        lightDirty = true;
    }
}
