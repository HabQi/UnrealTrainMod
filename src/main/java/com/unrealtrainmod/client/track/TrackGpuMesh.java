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
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import com.unrealtrainmod.client.compat.iris.IrisRenderTypes;
import com.unrealtrainmod.client.compat.iris.IrisRenderStats;
import com.unrealtrainmod.client.ClientModelManager;
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
    @Override public int source() { return TrackRenderStats.NATIVE; }
    @Override public int drawCalls() { return 1; }
    @Override public int drawVertices() { return IrisCompat.active() ? data.vertexCount() / 4 * 6 : data.vertexCount(); }
    @Override public int drawTriangles() { return data.vertexCount() / 2; }
    @Override public int storedVertices() { return IrisCompat.active() ? data.vertexCount() / 4 * 6 : data.vertexCount(); }

    @Override
    public int upload(ClientLevel level, int budget, TrackRenderStats stats) {
        if (!lightDirty) {
            long start = stats == null ? 0L : System.nanoTime();
            boolean changed = lighting.refresh(level);
            if (stats != null) {
                stats.lightRefresh(System.nanoTime() - start, lighting.checkedSamples(), lighting.changedSamples());
            }
            if (changed) lightDirty = true;
        }
        if (!lightDirty || budget == 0) return 0;
        upload(level, stats);
        lightDirty = false;
        return 1;
    }

    private void upload(ClientLevel level, TrackRenderStats stats) {
        if (IrisCompat.active()) {
            boolean first = buffer == null;
            if (first) buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            try {
                long start = stats == null ? 0 : System.nanoTime();
                int bytes = TrackIrisMesh.upload(buffer, data, null, 0, lighting, level);
                IrisRenderStats.upload(bytes);
                if (stats != null) {
                    stats.gpuUpload(System.nanoTime() - start);
                    stats.uploaded(bytes, 0, bytes, first);
                }
            } catch (RuntimeException | Error exception) { close(); throw exception; }
            return;
        }
        int vertexSize = DefaultVertexFormat.POSITION_COLOR_LIGHTMAP.getVertexSize();
        int capacity = Math.multiplyExact(data.vertexCount(), vertexSize);
        boolean first = buffer == null;
        if (first) buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try (var bytes = new ByteBufferBuilder(capacity)) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_LIGHTMAP);
            long packStart = stats == null ? 0L : System.nanoTime();
            for (int firstVertex = 0; firstVertex < data.vertexCount(); firstVertex += 4) {
                double ax = data.x(firstVertex + 1) - data.x(firstVertex), ay = data.y(firstVertex + 1) - data.y(firstVertex), az = data.z(firstVertex + 1) - data.z(firstVertex);
                double bx = data.x(firstVertex + 2) - data.x(firstVertex), by = data.y(firstVertex + 2) - data.y(firstVertex), bz = data.z(firstVertex + 2) - data.z(firstVertex);
                double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 0) { nx /= length; ny /= length; nz /= length; }
                for (int i = firstVertex; i < firstVertex + 4; i++) {
                    int light = lighting.sample(level, data.x(i), data.y(i), data.z(i), nx, ny, nz);
                    builder.addVertex(data.x(i), data.y(i), data.z(i)).setColor(data.color(i)).setLight(light);
                }
            }
            if (stats != null) stats.packed(System.nanoTime() - packStart);
            long uploadStart = stats == null ? 0L : System.nanoTime();
            buffer.bind();
            // VertexBuffer.upload owns and closes the temporary Minecraft MeshData.
            buffer.upload(builder.buildOrThrow());
            if (stats != null) {
                stats.gpuUpload(System.nanoTime() - uploadStart);
                stats.uploaded(capacity, 0, capacity, first);
            }
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        } finally {
            VertexBuffer.unbind();
        }
    }

    @Override public void drawIris(Matrix4f modelView, Matrix4f projection, TrackRenderStats stats) {
        boolean changed = IrisDrawScope.draw(buffer, IrisRenderTypes.get(ClientModelManager.WHITE, false, false, true), modelView, projection);
        if (stats != null) { if (changed) stats.renderTypeSetup(); stats.irisShaderBind(); }
    }

    @Override
    public void draw(Matrix4f modelView, Matrix4f projection, TrackRenderStats stats) {
        TrackRenderTypes.OPAQUE.setupRenderState();
        if (stats != null) stats.renderTypeSetup();
        try {
            buffer.bind();
            buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
            if (stats != null) stats.legacyShaderBind();
        } finally { TrackRenderTypes.OPAQUE.clearRenderState(); }
    }

    @Override
    public void draw(TrackRenderState state, Matrix4f modelView, Matrix4f projection) {
        state.drawNative(buffer, modelView, projection);
    }

    @Override
    public void enqueue(TrackDrawQueue queue, Matrix4f modelView, int structure) {
        queue.addNative(buffer, modelView, structure, data.vertexCount());
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
