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
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisRenderStats;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Endpoint geometry remains static for native morphs; UV2 always has its own stream. */
final class TrackMorphMesh implements TrackRenderMesh {
    private final TrackMeshData normal, reverse;
    private final AABB bounds;
    private final TrackLightBuffer lighting = new TrackLightBuffer(false);
    private VertexBuffer buffer;
    private boolean morph;
    private float movement, sampledMovement = Float.NaN;

    TrackMorphMesh(TrackMeshData normal, TrackMeshData reverse) {
        this.normal = normal;
        this.reverse = reverse;
        if (normal.vertexCount() != reverse.vertexCount()) throw new IllegalArgumentException("Turnout leaf topology differs");
        var a = normal.bounds(); var b = reverse.bounds();
        bounds = new AABB(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ())).inflate(0.001);
    }

    void movement(float value) { movement = value; }
    @Override public AABB bounds() { return bounds; }
    @Override public TrackVector origin() { return normal.origin(); }
    @Override public boolean isEmpty() { return normal.vertexCount() == 0; }
    boolean isUploaded() { return buffer != null; }
    @Override public int source() { return TrackRenderStats.NATIVE; }

    int upload(ClientLevel level, int budget, TrackRenderStats stats) {
        boolean shader = !IrisCompat.active() && TrackMorphShader.shader() != null;
        if (buffer != null && morph != shader) close();
        boolean first = buffer == null;
        if (first && budget == 0) return 0;
        boolean moved = movement != sampledMovement;
        boolean geometryChanged = first || (!shader && moved);
        morph = shader;
        try {
            if (geometryChanged) {
                if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                int size;
                if (IrisCompat.active()) {
                    var upload = TrackIrisMesh.upload(buffer, normal, reverse, movement, stats);
                    size = upload.bytes();
                    lighting.samples(upload.samples());
                    IrisRenderStats.upload(size);
                } else size = uploadNative(stats);
                lighting.attach(buffer);
                if (stats != null) {
                    if (first) stats.uploaded(size, 0, size);
                    else stats.activeUpload(size);
                }
            } else if (moved) {
                // Native morph movement changes sampling positions, never the endpoint VBO.
                var samples = new long[normal.vertexCount()];
                for (int i = 0; i < samples.length; i++) samples[i] = lightSample(i);
                lighting.samples(samples);
            }
            sampledMovement = movement;
            boolean changed = lighting.refresh(level, stats);
            if (changed && (first || moved || budget > 0)) {
                lighting.upload(stats, first, !first && moved);
                return first || !moved ? 1 : 0;
            }
            return first ? 1 : 0;
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        } finally { VertexBuffer.unbind(); }
    }

    private int uploadNative(TrackRenderStats stats) {
        var format = morph ? TrackMorphShader.FORMAT : DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;
        var samples = new long[normal.vertexCount()];
        int bytesCount = samples.length * format.getVertexSize();
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
                samples[i] = TrackLighting.key(o.x() + x, o.y() + y, o.z() + z, 0, 1, 0);
                if (morph) builder.addVertex(normal.x(i), normal.y(i), normal.z(i))
                        .setColor(normal.color(i)).setUv(rx, rz).setLight(0);
                else builder.addVertex(x, y, z).setColor(normal.color(i)).setLight(0);
            }
            lighting.samples(samples);
            if (stats != null) stats.packed(System.nanoTime() - start);
            start = stats == null ? 0 : System.nanoTime();
            buffer.bind();
            buffer.upload(builder.buildOrThrow());
            if (stats != null) stats.gpuUpload(System.nanoTime() - start);
        }
        return bytesCount;
    }

    private long lightSample(int i) {
        var o = origin(); var r = reverse.origin();
        float rx = (float)(reverse.x(i) + r.x() - o.x());
        float ry = (float)(reverse.y(i) + r.y() - o.y());
        float rz = (float)(reverse.z(i) + r.z() - o.z());
        float x = normal.x(i) + (rx - normal.x(i)) * movement;
        float y = normal.y(i) + (ry - normal.y(i)) * movement;
        float z = normal.z(i) + (rz - normal.z(i)) * movement;
        return TrackLighting.key(o.x() + x, o.y() + y, o.z() + z, 0, 1, 0);
    }

    void enqueue(TrackDrawQueue queue, Matrix4f modelView, int structure) {
        if (morph) queue.addMorph(buffer, modelView, structure, normal.vertexCount(), movement);
        else queue.addNative(buffer, modelView, structure, normal.vertexCount());
    }

    @Override public void close() {
        try { lighting.close(); }
        finally { if (buffer != null) { buffer.close(); buffer = null; } }
        sampledMovement = Float.NaN;
    }
}
