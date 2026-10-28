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
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** GPU lifetime is separate from the CPU geometry, so resource reloads need no server resync. */
final class TrackGpuMesh implements AutoCloseable {
    final TrackMeshData data;
    final AABB bounds;
    private VertexBuffer buffer;

    TrackGpuMesh(TrackMeshData data) {
        this.data = data;
        var box = data.bounds();
        bounds = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()).inflate(0.001);
    }

    boolean isUploaded() { return buffer != null; }

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

    void draw(Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
