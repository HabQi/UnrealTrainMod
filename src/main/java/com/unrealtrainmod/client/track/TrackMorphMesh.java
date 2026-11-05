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
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Both endpoint positions stay on the GPU. The fallback streams only the two moving leaves. */
final class TrackMorphMesh implements TrackRenderMesh {
    private final TrackMeshData normal, reverse;
    private final AABB bounds;
    private final TrackLighting lighting;
    private VertexBuffer buffer;
    private boolean morph, lightDirty = true;
    private float movement, uploadedMovement = Float.NaN;

    TrackMorphMesh(TrackMeshData normal, TrackMeshData reverse) {
        this.normal = normal;
        this.reverse = reverse;
        if (normal.vertexCount() != reverse.vertexCount()) throw new IllegalArgumentException("Turnout leaf topology differs");
        var a = normal.bounds(); var b = reverse.bounds();
        bounds = new AABB(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ())).inflate(0.001);
        lighting = new TrackLighting(normal.origin());
    }

    void movement(float value) { movement = value; }
    @Override public AABB bounds() { return bounds; }
    @Override public TrackVector origin() { return normal.origin(); }
    @Override public boolean isEmpty() { return normal.vertexCount() == 0; }
    @Override public boolean isUploaded() { return buffer != null; }
    @Override public int source() { return TrackRenderStats.NATIVE; }
    @Override public int drawCalls() { return 1; }
    @Override public int drawVertices() { return normal.vertexCount(); }
    @Override public int drawTriangles() { return normal.vertexCount() / 2; }
    @Override public int storedVertices() { return normal.vertexCount(); }

    @Override public int upload(ClientLevel level, int budget, TrackRenderStats stats) {
        boolean shader = TrackMorphShader.shader() != null;
        long lightStart = stats == null ? 0 : System.nanoTime();
        lightDirty |= lighting.refresh(level);
        if (stats != null) stats.lightRefresh(System.nanoTime() - lightStart, lighting.checkedSamples(), lighting.changedSamples());
        if (buffer != null && morph != shader) close();
        boolean first = buffer == null;
        boolean changed = !shader && movement != uploadedMovement;
        if (!first && !lightDirty && !changed) return 0;
        // Streaming leaves must keep up with display time even when static uploads exhaust their budget.
        if (first && budget == 0) return 0;
        morph = shader;
        var format = morph ? TrackMorphShader.FORMAT : DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;
        int bytesCount = normal.vertexCount() * format.getVertexSize();
        if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        try (var bytes = new ByteBufferBuilder(bytesCount)) {
            long start = stats == null ? 0 : System.nanoTime();
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
            var o = origin(); var r = reverse.origin();
            for (int i = 0; i < normal.vertexCount(); i++) {
                float rx = (float)(reverse.x(i) + r.x() - o.x());
                float ry = (float)(reverse.y(i) + r.y() - o.y());
                float rz = (float)(reverse.z(i) + r.z() - o.z());
                float x = normal.x(i) + (rx - normal.x(i)) * movement;
                float y = normal.y(i) + (ry - normal.y(i)) * movement;
                float z = normal.z(i) + (rz - normal.z(i)) * movement;
                int light = lighting.sample(level, x, y, z, 0, 1, 0);
                if (morph) builder.addVertex(normal.x(i), normal.y(i), normal.z(i))
                        .setColor(normal.color(i)).setUv(rx, rz).setLight(light);
                else builder.addVertex(x, y, z).setColor(normal.color(i)).setLight(light);
            }
            if (stats != null) stats.packed(System.nanoTime() - start);
            start = stats == null ? 0 : System.nanoTime();
            buffer.bind();
            buffer.upload(builder.buildOrThrow());
            if (stats != null) {
                stats.gpuUpload(System.nanoTime() - start);
                stats.activeUpload(bytesCount);
            }
            uploadedMovement = movement;
            lightDirty = false;
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        } finally { VertexBuffer.unbind(); }
        return first ? 1 : 0;
    }

    @Override public void draw(Matrix4f modelView, Matrix4f projection, TrackRenderStats stats) {
        try (var state = new TrackRenderState(stats)) { draw(state, modelView, projection); }
    }
    @Override public void draw(TrackRenderState state, Matrix4f modelView, Matrix4f projection) {
        if (morph) state.drawMorph(buffer, modelView, projection, movement);
        else state.drawNative(buffer, modelView, projection);
    }
    @Override public void enqueue(TrackDrawQueue queue, Matrix4f modelView, int structure) {
        if (morph) queue.addMorph(buffer, modelView, structure, normal.vertexCount(), movement);
        else queue.addNative(buffer, modelView, structure, normal.vertexCount());
    }
    @Override public void close() {
        if (buffer != null) { buffer.close(); buffer = null; }
        lightDirty = true;
        uploadedMovement = Float.NaN;
    }
}
