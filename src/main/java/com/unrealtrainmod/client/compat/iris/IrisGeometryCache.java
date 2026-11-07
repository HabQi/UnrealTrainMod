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
    private static final long LIGHT_SOFT_LIMIT = 64L * 1024 * 1024;
    /** Helian's emissive surface multiplies the tagged color by its PBR brightness term. */
    static final float LIGHT_COLOR_SCALE = 0.55f;
    private record Key(IndexedMesh mesh, int first, int count, boolean opaque, boolean light) {}
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
    private static long frame, bytes, lightBytes, peakBytes, windowBytes, windowBuilds, windowNanos,
            windowLightEvictions, lastLog = System.nanoTime();

    private IrisGeometryCache() {}
    public static long frame() { return frame; }
    public static long bytes() { return bytes; }
    public static long lightBytes() { return lightBytes; }
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
        return request(mesh, first, count, opaque, false, false);
    }

    /** A vehicle miss builds once now, avoiding repeated full CPU submissions in both views while queued. */
    public static Mesh requestVehicle(IndexedMesh mesh, int first, int count, boolean opaque) {
        return request(mesh, first, count, opaque, true, false);
    }

    /** Static items share local geometry across hand, entity and shadow views. */
    public static Mesh requestItem(IndexedMesh mesh, int first, int count) {
        return request(mesh, first, count, false, true, false);
    }

    public static void releaseMesh(IndexedMesh mesh) {
        var iterator = ENTRIES.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.key.mesh != mesh) continue;
            PENDING.remove(entry);
            if (entry.gpu != null) {
                bytes -= entry.gpu.bytes;
                if (entry.key.light) lightBytes -= entry.gpu.bytes;
                entry.gpu.close();
            }
            iterator.remove();
        }
    }

    /** Lights have their own soft budget; the active working set must not fall back to CPU for lack of space. */
    public static Mesh requestVehicleLight(IndexedMesh mesh, int first, int count) {
        return request(mesh, first, count, false, true, true);
    }

    private static Mesh request(IndexedMesh mesh, int first, int count, boolean opaque, boolean immediate, boolean light) {
        var key = new Key(mesh, first, count, opaque, light);
        var entry = ENTRIES.get(key);
        boolean hit = entry != null && entry.gpu != null;
        if (entry == null) {
            entry = new Entry(key);
            ENTRIES.put(key, entry);
            if (immediate) {
                if (light) trimLights((long)count * IrisCompat.entityVertexSize());
                build(entry);
            } else PENDING.addLast(entry);
        }
        entry.requestedFrame = frame;
        IrisRenderStats.cache(hit, light);
        return entry.gpu;
    }

    /** Only invoked in menus. Admission is bounded; on-demand world entries retain the full soft limit. */
    static boolean prewarm(IndexedMesh mesh, int first, int count, boolean opaque) {
        var key = new Key(mesh, first, count, opaque, false);
        var entry = ENTRIES.get(key);
        if (entry != null) return entry.gpu != null;
        if (bytes + (long)count * IrisCompat.entityVertexSize() > LIMIT / 2) return false;
        entry = new Entry(key);
        ENTRIES.put(key, entry);
        build(entry);
        return entry.gpu != null;
    }

    private static void build(Entry entry) {
        long begun = System.nanoTime();
        try {
            entry.gpu = upload(entry.key);
            bytes += entry.gpu.bytes;
            if (entry.key.light) lightBytes += entry.gpu.bytes;
            peakBytes = Math.max(peakBytes, bytes);
            windowBytes += entry.gpu.bytes;
            IrisRenderStats.upload(entry.gpu.bytes, entry.key.light);
        } catch (RuntimeException exception) {
            LoggerFactory.getLogger(IrisGeometryCache.class).warn("Cannot cache Iris geometry; keeping CPU submission", exception);
        }
        windowNanos += System.nanoTime() - begun;
        windowBuilds++;
    }

    /** Runs after world submission for queued rigid track parts; their CPU fallback remains until ready. */
    public static void endFrame() {
        long start = System.nanoTime();
        int built = 0;
        while (!PENDING.isEmpty() && built < 4 && System.nanoTime() - start < 2_000_000L) {
            var entry = PENDING.removeFirst();
            if (entry.requestedFrame < frame - 1) { ENTRIES.remove(entry.key); continue; }
            build(entry);
            built++;
        }
        long baseBytes = bytes - lightBytes;
        if (baseBytes > LIMIT) {
            var iterator = ENTRIES.values().iterator();
            while (baseBytes > LIMIT && iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.gpu == null || entry.key.light || entry.requestedFrame >= frame - 1) continue;
                bytes -= entry.gpu.bytes;
                baseBytes -= entry.gpu.bytes;
                entry.gpu.close();
                iterator.remove();
            }
        }
        trimLights(0);
        if (System.nanoTime() - lastLog >= 5_000_000_000L) {
            if (ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean() || ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) {
                LoggerFactory.getLogger("UnrealTrainMod/Iris").info(
                        "Iris geometry cache: resident={}B lightResident={}B peak={}B softLimit={}B lightSoftLimit={}B lightOverBudget={}B lightEvictions={} pending={} builds={} upload={}B buildCpu={}ms",
                        bytes, lightBytes, peakBytes, LIMIT, LIGHT_SOFT_LIMIT, Math.max(0L, lightBytes - LIGHT_SOFT_LIMIT),
                        windowLightEvictions, PENDING.size(), windowBuilds, windowBytes, windowNanos / 1_000_000.0);
            }
            lastLog = System.nanoTime();
            windowBytes = windowBuilds = windowNanos = windowLightEvictions = 0;
        }
    }

    private static Mesh upload(Key key) {
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int[] identity = IrisCompat.currentIdentity();
        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try (var storage = new ByteBufferBuilder(Math.max(256,
                Math.multiplyExact(key.count, IrisCompat.entityVertexSize())))) {
            IrisCompat.restoreTrackIdentity(new int[3]);
            var builder = new BufferBuilder(storage, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            var v = key.mesh.vertices(); var colors = key.mesh.colors(); var indices = key.mesh.indices();
            for (int i = key.first; i < key.first + key.count; i++) {
                int index = indices[i], offset = index * 8;
                int color = key.light ? scaleRgb(colors[index]) : colors[index];
                builder.addVertex(v[offset], v[offset + 1], v[offset + 2], key.opaque ? color | 0xff000000 : color,
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

    /** Evict idle lights in LRU order; current/previous-frame meshes may exceed the soft budget. */
    private static void trimLights(long incomingBytes) {
        if (lightBytes + incomingBytes <= LIGHT_SOFT_LIMIT) return;
        var iterator = ENTRIES.values().iterator();
        while (iterator.hasNext() && lightBytes + incomingBytes > LIGHT_SOFT_LIMIT) {
            var candidate = iterator.next();
            if (candidate.gpu == null || !candidate.key.light || candidate.requestedFrame >= frame - 1) continue;
            bytes -= candidate.gpu.bytes;
            lightBytes -= candidate.gpu.bytes;
            candidate.gpu.close();
            iterator.remove();
            windowLightEvictions++;
        }
    }

    private static int scaleRgb(int color) {
        int red = Math.round(((color >>> 16) & 0xff) * LIGHT_COLOR_SCALE);
        int green = Math.round(((color >>> 8) & 0xff) * LIGHT_COLOR_SCALE);
        int blue = Math.round((color & 0xff) * LIGHT_COLOR_SCALE);
        return color & 0xff000000 | red << 16 | green << 8 | blue;
    }

    public static void clear() {
        ENTRIES.values().forEach(entry -> { if (entry.gpu != null) entry.gpu.close(); });
        ENTRIES.clear(); PENDING.clear();
        bytes = lightBytes = peakBytes = windowBytes = windowBuilds = windowNanos = windowLightEvictions = 0;
        lastLog = System.nanoTime();
        IrisBatch.clear();
        IrisPrewarmer.clear();
    }
}
