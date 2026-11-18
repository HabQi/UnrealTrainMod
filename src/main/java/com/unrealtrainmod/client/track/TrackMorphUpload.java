/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.ModelPreparation;
import com.unrealtrainmod.client.ModelUploads;
import com.unrealtrainmod.client.StagedVertexBuffer;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** First leaf geometry is prepared off-thread; later animation retains the existing update path. */
final class TrackMorphUpload implements AutoCloseable {
    private static final int[] CORNERS = {0, 1, 2, 0, 2, 3};
    private record Prepared(float[] vertices, int[] colors, long[] samples, ByteBuffer packed, ByteBuffer indices) implements AutoCloseable {
        long bytes() { return (long)vertices.length * 4 + (long)colors.length * 4 + (long)samples.length * 8
                + (packed == null ? 0 : packed.capacity() + (long)indices.capacity()); }
        @Override public void close() { MemoryUtil.memFree(packed); MemoryUtil.memFree(indices); }
    }
    private final ModelPreparation.Ticket<Prepared> pending;
    private final boolean iris, morph;
    private final TrackLightBuffer light;
    private TrackPageBuffer buffer;
    private int phase, offset;
    private boolean transferred;

    static TrackMorphUpload request(TrackMeshData normal, TrackMeshData reverse, float movement,
                                   boolean iris, boolean morph, TrackLightBuffer light) {
        var ticket = ModelPreparation.geometry(() -> prepare(normal, reverse, movement, iris, morph), Prepared::bytes, Prepared::close);
        return ticket == null ? null : new TrackMorphUpload(ticket, iris, morph, light);
    }
    private TrackMorphUpload(ModelPreparation.Ticket<Prepared> pending, boolean iris, boolean morph, TrackLightBuffer light) {
        this.pending = pending; this.iris = iris; this.morph = morph; this.light = light;
    }
    private static Prepared prepare(TrackMeshData normal, TrackMeshData reverse, float movement, boolean iris, boolean morph) {
        int count = iris ? normal.vertexCount() / 4 * 6 : normal.vertexCount();
        var vertices = new float[count * 8]; var colors = new int[count]; var samples = new long[count];
        var origin = normal.origin(); var r = reverse.origin();
        var points = new Vector3f[] {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        var a = new Vector3f(); var b = new Vector3f();
        int cursor = 0;
        for (int face = 0; face < normal.vertexCount(); face += 4) {
            for (int corner = 0; corner < 4; corner++) {
                int i = face + corner;
                points[corner].set(normal.x(i), normal.y(i), normal.z(i)).lerp(b.set(
                        (float)(reverse.x(i) + r.x() - origin.x()), (float)(reverse.y(i) + r.y() - origin.y()),
                        (float)(reverse.z(i) + r.z() - origin.z())), movement);
            }
            points[1].sub(points[0], a); points[2].sub(points[0], b); a.cross(b).normalize();
            for (int reference = 0; reference < (iris ? 6 : 4); reference++) {
                int corner = iris ? CORNERS[reference] : reference, i = face + corner, v = cursor * 8;
                var point = points[corner];
                vertices[v] = morph ? normal.x(i) : point.x;
                vertices[v + 1] = morph ? normal.y(i) : point.y;
                vertices[v + 2] = morph ? normal.z(i) : point.z;
                vertices[v + 3] = morph ? (float)(reverse.x(i) + r.x() - origin.x()) : corner == 1 || corner == 2 ? 1 : 0;
                vertices[v + 4] = morph ? (float)(reverse.z(i) + r.z() - origin.z()) : corner >= 2 ? 1 : 0;
                vertices[v + 5] = a.x; vertices[v + 6] = a.y; vertices[v + 7] = a.z;
                colors[cursor] = iris ? normal.baseColor(i) : normal.color(i);
                samples[cursor] = TrackLighting.key(origin.x() + point.x, origin.y() + point.y, origin.z() + point.z,
                        iris ? a.x : 0, iris ? a.y : 1, iris ? a.z : 0);
                cursor++;
            }
        }
        ByteBuffer packed = null, indices = null;
        try {
            if (!iris) {
                packed = MemoryUtil.memAlloc(count * (morph ? 28 : 20)).order(ByteOrder.nativeOrder());
                for (int i = 0; i < count; i++) {
                    int v = i * 8, color = colors[i];
                    packed.putFloat(vertices[v]).putFloat(vertices[v + 1]).putFloat(vertices[v + 2]);
                    packed.put((byte)(color >>> 16)).put((byte)(color >>> 8)).put((byte)color).put((byte)(color >>> 24));
                    if (morph) packed.putFloat(vertices[v + 3]).putFloat(vertices[v + 4]);
                    packed.putInt(0);
                }
                packed.flip();
                boolean shorts = count <= 65_536;
                indices = MemoryUtil.memAlloc(count / 4 * 6 * (shorts ? 2 : 4)).order(ByteOrder.nativeOrder());
                for (int face = 0; face < count; face += 4) for (int corner : CORNERS) {
                    if (shorts) indices.putShort((short)(face + corner)); else indices.putInt(face + corner);
                }
                indices.flip();
            }
            return new Prepared(vertices, colors, samples, packed, indices);
        } catch (RuntimeException | Error exception) { MemoryUtil.memFree(packed); MemoryUtil.memFree(indices); throw exception; }
    }

    boolean step(ClientLevel level, TrackRenderStats stats) {
        if (!pending.ready()) return false;
        var data = pending.value();
        if (phase == 3) return true;
        int stride = iris ? IrisCompat.entityVertexSize() : morph ? 28 : 20;
        int size = phase == 0 ? Math.min(ModelUploads.SLICE_BYTES, data.colors.length * stride - offset)
                : phase == 1 ? Math.min(ModelUploads.SLICE_BYTES, data.indices.capacity() - offset)
                : Math.min(8192, data.colors.length - offset) * 4;
        if (iris && phase == 0) size = Math.min(data.colors.length - offset / stride, ModelUploads.SLICE_BYTES / stride / 3 * 3) * stride;
        int amount = size;
        return ModelUploads.run(amount, () -> {
            try (var binding = new StagedVertexBuffer.Binding()) {
                if (buffer == null) initialize(data, stride);
                if (phase == 0) {
                    if (iris) {
                        int[] identity = IrisCompat.currentIdentity();
                        long start = System.nanoTime();
                        try (var bytes = new ByteBufferBuilder(Math.max(256, amount))) {
                            IrisCompat.restoreTrackIdentity(new int[3]);
                            var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                            for (int i = offset / stride; i < (offset + amount) / stride; i++) emit(builder, data, i);
                            ModelUploads.packed(System.nanoTime() - start);
                            try (var built = builder.buildOrThrow()) { buffer.write(built.vertexBuffer(), offset); }
                        } finally { IrisCompat.restoreTrackIdentity(identity); }
                    } else buffer.write(data.packed.duplicate().position(offset).limit(offset + amount).slice(), offset);
                    offset += amount;
                    if (offset == data.colors.length * stride) { phase = iris ? 2 : 1; offset = 0; }
                } else if (phase == 1) {
                    buffer.writeIndices(data.indices.duplicate().position(offset).limit(offset + amount).slice(), offset);
                    offset += amount;
                    if (offset == data.indices.capacity()) { phase = 2; offset = 0; }
                } else {
                    light.initial(level, offset, amount / 4, stats); offset += amount / 4;
                    if (offset == data.colors.length) { phase = 3; offset = 0; }
                }
            }
        }) && phase == 3;
    }
    private void initialize(Prepared data, int stride) {
        buffer = new TrackPageBuffer(iris, data.colors.length);
        try (var bytes = new ByteBufferBuilder(stride * 3)) {
            if (iris) {
                int[] identity = IrisCompat.currentIdentity();
                try {
                    IrisCompat.restoreTrackIdentity(new int[3]);
                    var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                    for (int i = 0; i < 3; i++) emit(builder, data, i);
                    buffer.allocate(builder.buildOrThrow(), data.colors.length * stride);
                    IrisCompat.requireEntityFormat(buffer);
                } finally { IrisCompat.restoreTrackIdentity(identity); }
            } else {
                MemoryUtil.memByteBuffer(bytes.reserve(stride * 3), stride * 3).put(data.packed.duplicate().limit(stride * 3));
                buffer.allocate(new MeshData(bytes.build(), new MeshData.DrawState(morph ? TrackMorphShader.FORMAT : DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
                        3, 3, VertexFormat.Mode.TRIANGLES, VertexFormat.IndexType.INT)), data.colors.length * stride);
                buffer.allocateIndices(data.indices.capacity(), data.colors.length);
            }
        }
        light.samples(data.samples); light.attach(buffer);
    }
    private static void emit(BufferBuilder builder, Prepared data, int index) {
        int i = index * 8; var v = data.vertices;
        builder.addVertex(v[i], v[i + 1], v[i + 2], data.colors[index], v[i + 3], v[i + 4],
                OverlayTexture.NO_OVERLAY, 0, v[i + 5], v[i + 6], v[i + 7]);
    }
    VertexBuffer finish(TrackRenderStats stats) {
        transferred = true;
        if (stats != null) stats.uploaded(pending.value().colors.length * (iris ? IrisCompat.entityVertexSize() : morph ? 28 : 20),
                iris ? 0 : pending.value().indices.capacity(), 0);
        pending.close();
        return buffer;
    }
    @Override public void close() {
        pending.close();
        if (!transferred && buffer != null) buffer.close();
    }
}
