/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.model.IndexedMesh;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.slf4j.LoggerFactory;
import org.joml.Matrix4f;

/** Local geometry only. Light, identity, view matrices and shaders never belong to this cache. */
public final class IrisGeometryCache {
    private static final long LIMIT = 256L * 1024 * 1024;
    private record Key(IndexedMesh mesh, int first, int count, boolean opaque) {}
    private static final class Entry {
        final Key key;
        long requestedFrame;
        Mesh gpu;
        Entry(Key key) { this.key = key; }
    }
    public static final class Mesh implements AutoCloseable {
        final VertexBuffer buffer;
        final int lightAttribute, identityAttribute;
        public final int vertices;
        final int bytes;

        private Mesh(VertexBuffer buffer, int vertices, int bytes) {
            this.buffer = buffer;
            this.vertices = vertices;
            this.bytes = bytes;
            var names = buffer.getFormat().getElementAttributeNames();
            lightAttribute = names.indexOf("UV2");
            identityAttribute = names.indexOf("iris_Entity");
            if (lightAttribute < 0 || identityAttribute < 0) throw new IllegalStateException("Iris entity format was not extended");
            // These arrays are intentionally disabled only on our private VAO.
            GlStateManager._disableVertexAttribArray(lightAttribute);
            GlStateManager._disableVertexAttribArray(identityAttribute);
        }
        @Override public void close() { buffer.close(); }
    }

    private static final LinkedHashMap<Key, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75f, true);
    private static final ArrayDeque<Entry> PENDING = new ArrayDeque<>();
    private static long frame, bytes, peakBytes, windowBytes, windowBuilds, windowNanos, lastLog = System.nanoTime();

    private IrisGeometryCache() {}
    public static long frame() { return frame; }
    public static long bytes() { return bytes; }
    public static long peakBytes() { return peakBytes; }
    public static int pending() { return PENDING.size(); }

    public static void beginFrame() { frame++; }

    /** Iris packs transform tangent XYZ with the normal matrix; reflected/sheared scales need CPU tangents. */
    public static boolean supportsPose(Matrix4f m) {
        float x = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
        float y = m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12();
        float z = m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22();
        float tolerance = x * 0.0001f;
        return m.determinant3x3() > 0 && Math.abs(x - y) <= tolerance && Math.abs(x - z) <= tolerance
                && Math.abs(m.m00() * m.m10() + m.m01() * m.m11() + m.m02() * m.m12()) <= tolerance
                && Math.abs(m.m00() * m.m20() + m.m01() * m.m21() + m.m02() * m.m22()) <= tolerance
                && Math.abs(m.m10() * m.m20() + m.m11() * m.m21() + m.m12() * m.m22()) <= tolerance;
    }

    public static Mesh request(IndexedMesh mesh, int first, int count, boolean opaque) {
        var key = new Key(mesh, first, count, opaque);
        var entry = ENTRIES.get(key);
        if (entry == null) {
            entry = new Entry(key);
            ENTRIES.put(key, entry);
            PENDING.addLast(entry);
        }
        entry.requestedFrame = frame;
        IrisRenderStats.cache(entry.gpu != null);
        return entry.gpu;
    }

    /** Runs after world submission. Misses continue drawing through the CPU path until ready. */
    public static void endFrame() {
        long start = System.nanoTime();
        int built = 0;
        while (!PENDING.isEmpty() && built < 4 && System.nanoTime() - start < 2_000_000L) {
            var entry = PENDING.removeFirst();
            if (entry.requestedFrame < frame - 1) { ENTRIES.remove(entry.key); continue; }
            long begun = System.nanoTime();
            try {
                entry.gpu = upload(entry.key);
                bytes += entry.gpu.bytes;
                peakBytes = Math.max(peakBytes, bytes);
                windowBytes += entry.gpu.bytes;
            } catch (RuntimeException exception) {
                LoggerFactory.getLogger(IrisGeometryCache.class).warn("Cannot cache Iris geometry; keeping CPU submission", exception);
            }
            windowNanos += System.nanoTime() - begun;
            windowBuilds++;
            built++;
        }
        if (bytes > LIMIT) {
            var iterator = ENTRIES.values().iterator();
            while (bytes > LIMIT && iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.gpu == null || entry.requestedFrame >= frame - 1) continue;
                bytes -= entry.gpu.bytes;
                entry.gpu.close();
                iterator.remove();
            }
        }
        if (System.nanoTime() - lastLog >= 5_000_000_000L) {
            if (ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean() || ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) {
                LoggerFactory.getLogger("UnrealTrainMod/Iris").info(
                        "Iris geometry cache: resident={}B peak={}B softLimit={}B pending={} builds={} upload={}B buildCpu={}ms",
                        bytes, peakBytes, LIMIT, PENDING.size(), windowBuilds, windowBytes, windowNanos / 1_000_000.0);
            }
            lastLog = System.nanoTime();
            windowBytes = windowBuilds = windowNanos = 0;
        }
    }

    private static Mesh upload(Key key) {
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int[] identity = IrisCompat.currentIdentity();
        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try (var storage = new ByteBufferBuilder(Math.max(256,
                Math.multiplyExact(key.count, DefaultVertexFormat.NEW_ENTITY.getVertexSize())))) {
            IrisCompat.restoreTrackIdentity(new int[3]);
            var builder = new BufferBuilder(storage, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            var v = key.mesh.vertices(); var colors = key.mesh.colors(); var indices = key.mesh.indices();
            for (int i = key.first; i < key.first + key.count; i++) {
                int index = indices[i], offset = index * 8;
                builder.addVertex(v[offset], v[offset + 1], v[offset + 2], key.opaque ? colors[index] | 0xff000000 : colors[index],
                        v[offset + 3], v[offset + 4], OverlayTexture.NO_OVERLAY, 0,
                        v[offset + 5], v[offset + 6], v[offset + 7]);
            }
            buffer.bind();
            var data = builder.buildOrThrow();
            int size = data.vertexBuffer().remaining();
            buffer.upload(data);
            return new Mesh(buffer, key.count, size);
        } catch (RuntimeException | Error exception) {
            buffer.close();
            throw exception;
        } finally {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(oldVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArray);
            IrisCompat.restoreTrackIdentity(identity);
        }
    }

    public static void clear() {
        ENTRIES.values().forEach(entry -> { if (entry.gpu != null) entry.gpu.close(); });
        ENTRIES.clear(); PENDING.clear();
        bytes = peakBytes = windowBytes = windowBuilds = windowNanos = 0;
        lastLog = System.nanoTime();
        IrisBatch.clear();
    }
}
