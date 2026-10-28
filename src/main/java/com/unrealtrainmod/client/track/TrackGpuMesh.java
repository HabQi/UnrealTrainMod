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
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** GPU lifetime is separate from the CPU geometry, so resource reloads need no server resync. */
final class TrackGpuMesh implements TrackRenderMesh {
    final TrackMeshData data;
    final AABB bounds;
    private VertexBuffer buffer;

    TrackGpuMesh(TrackMeshData data) {
        this.data = data;
        var box = data.bounds();
        bounds = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).inflate(0.001);
    }

    @Override public boolean isUploaded() { return buffer != null; }
    @Override public boolean isEmpty() { return data.vertexCount() == 0; }
    @Override public AABB bounds() { return bounds; }
    @Override public TrackVector origin() { return data.origin(); }

    @Override
    public int upload(int budget) {
        if (isUploaded() || budget == 0) return 0;
        upload();
        return 1;
    }

    void upload() {
        buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try (var bytes = new ByteBufferBuilder(Math.multiplyExact(data.vertexCount(), DefaultVertexFormat.POSITION_COLOR.getVertexSize()))) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < data.vertexCount(); i++) {
                builder.addVertex(data.x(i), data.y(i), data.z(i)).setColor(data.color(i));
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
    }
}
