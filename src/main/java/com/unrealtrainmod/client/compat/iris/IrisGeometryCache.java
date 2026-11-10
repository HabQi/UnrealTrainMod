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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.slf4j.LoggerFactory;
import org.joml.Matrix4f;

/** Local geometry only. Light, identity, view matrices and shaders never belong to this cache. */
public final class IrisGeometryCache {
    private static final long LIGHT_SOFT_LIMIT = 64L * 1024 * 1024;
    /** Helian's emissive surface multiplies the tagged color by its PBR brightness term. */
    static final float LIGHT_COLOR_SCALE = 0.55f;
    private record Key(IndexedMesh mesh, int first, int count, boolean opaque, boolean light) {}
    private record SharedKey(IndexedMesh mesh, boolean opaque) {}
    private record Span(int first, int count) {}
    private record Range(int first, Mesh mesh) {}
    private record Layout(List<Range> ranges, List<Span> spans, int[] offsets, int vertices) {}
    private static final Comparator<Range> RANGE_ORDER = Comparator.comparingInt(Range::first)
            .thenComparingInt(range -> range.mesh.vertices);
    private static final class SharedEntry {
        final SharedKey key;
        final Storage storage = new Storage();
        final Long2ObjectOpenHashMap<Range> ranges = new Long2ObjectOpenHashMap<>();
        Layout layout;
        long requestedFrame = -1;
        long requestedNanos;
        boolean dirty;
        SharedEntry(SharedKey key) { this.key = key; }
    }
    private static final class Entry {
        final Key key;
        long requestedFrame;
        long requestedNanos;
        Mesh gpu;
        Entry(Key key) {
            this.key = key;
            requestedFrame = frame;
            requestedNanos = frameNanos;
        }
    }
    static final class Storage implements AutoCloseable {
        final long order = nextStorageOrder++;
        VertexBuffer buffer;
        int lightAttribute, identityAttribute, bytes;
        Storage() {}
        Storage(VertexBuffer buffer, int bytes) {
            this.buffer = buffer;
            this.bytes = bytes;
            var names = buffer.getFormat().getElementAttributeNames();
            lightAttribute = names.indexOf("UV2");
            identityAttribute = names.indexOf("iris_Entity");
            if (lightAttribute < 0 || identityAttribute < 0) throw new IllegalStateException("Iris entity format was not extended");
            // These arrays are intentionally disabled only on our private VAO.
            GlStateManager._disableVertexAttribArray(lightAttribute);
            GlStateManager._disableVertexAttribArray(identityAttribute);
        }
        void replace(Storage next) {
            close();
            buffer = next.buffer;
            next.buffer = null;
            lightAttribute = next.lightAttribute;
            identityAttribute = next.identityAttribute;
            bytes = next.bytes;
        }
        @Override public void close() { if (buffer != null) { buffer.close(); buffer = null; } }
    }
    /** Range views do not own buffers. Cache entries release each storage exactly once. */
    public static final class Mesh {
        final Storage storage;
        final boolean shared;
        int firstVertex;
        public final int vertices;
        private Mesh(Storage storage, int firstVertex, int vertices, boolean shared) {
            this.storage = storage;
            this.firstVertex = firstVertex;
            this.vertices = vertices;
            this.shared = shared;
        }
        boolean ready() { return firstVertex >= 0; }
    }

    private static final LinkedHashMap<Key, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75f, true);
    private static final ArrayDeque<Entry> PENDING = new ArrayDeque<>();
    private static final LinkedHashMap<SharedKey, SharedEntry> SHARED = new LinkedHashMap<>(64, 0.75f, true);
    private static final ArrayDeque<SharedEntry> DIRTY = new ArrayDeque<>();
    private static long frame, bytes, lightBytes, peakBytes, windowBytes, windowBuilds, windowNanos,
            windowMaxBuildNanos, windowBaseEvictions, windowEvictedBytes, windowLightEvictions,
            lastLog = System.nanoTime(), frameNanos = lastLog;
    private static long sharedBytes, sharedBuilds, sharedRebuilds, sharedUploads, sharedAdded;
    private static int sharedBuffers, sharedRanges;
    private static long nextStorageOrder;

    private IrisGeometryCache() {}
    public static long frame() { return frame; }
    public static long bytes() { return bytes; }
    public static long lightBytes() { return lightBytes; }
    public static long peakBytes() { return peakBytes; }
    public static int pending() { return PENDING.size() + DIRTY.size(); }

    public static void beginFrame() {
        frame++;
        frameNanos = System.nanoTime();
    }

    private static long limitBytes() {
        return (long) ClientConfig.IRIS_GEOMETRY_CACHE_MIB.get() * 1024 * 1024;
    }

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

    /** Register all ranges for a prepared source before uploading, including menu-known CUTOUT selections. */
    static void registerVehicleOpaque(IndexedMesh mesh, int first, int count, boolean opaque) {
        registerOpaque(mesh, first, count, opaque);
    }

    private static SharedEntry registerOpaque(IndexedMesh mesh, int first, int count, boolean opaque) {
        var key = new SharedKey(mesh, opaque);
        var entry = SHARED.computeIfAbsent(key, SharedEntry::new);
        long rangeKey = ((long) first << 32) | (count & 0xffffffffL);
        if (!entry.ranges.containsKey(rangeKey)) {
            entry.ranges.put(rangeKey, new Range(first, new Mesh(entry.storage, -1, count, true)));
            entry.layout = null;
            sharedRanges++;
        }
        return entry;
    }

    /** Returned views become ready after uploadVehicleOpaque(), once the submission registered all ranges. */
    static Mesh requestVehicleOpaque(IndexedMesh mesh, int first, int count, boolean opaque) {
        var entry = registerOpaque(mesh, first, count, opaque);
        var view = entry.ranges.get(((long) first << 32) | (count & 0xffffffffL)).mesh;
        entry.requestedFrame = frame;
        entry.requestedNanos = frameNanos;
        IrisRenderStats.cache(view.ready());
        if (!view.ready() && !entry.dirty) {
            entry.dirty = true;
            DIRTY.addLast(entry);
        }
        return view;
    }

    static boolean uploadVehicleOpaque() {
        boolean complete = true;
        while (!DIRTY.isEmpty()) {
            var entry = DIRTY.removeFirst();
            entry.dirty = false;
            long begun = System.nanoTime();
            try {
                var layout = layout(entry);
                var uploaded = upload(entry.key.mesh, layout.spans, layout.vertices, entry.key.opaque, false);
                int previous = entry.storage.bytes;
                boolean replacing = entry.storage.buffer != null;
                entry.storage.replace(uploaded);
                for (int i = 0; i < layout.ranges.size(); i++) layout.ranges.get(i).mesh.firstVertex = layout.offsets[i];
                long added = (long) uploaded.bytes - previous;
                bytes += added;
                sharedBytes += added;
                if (!replacing) sharedBuffers++;
                else sharedRebuilds++;
                sharedBuilds++;
                sharedUploads += uploaded.bytes;
                sharedAdded += added;
                windowBytes += uploaded.bytes;
                peakBytes = Math.max(peakBytes, bytes);
                IrisRenderStats.upload(uploaded.bytes);
            } catch (RuntimeException exception) {
                complete = false;
                LoggerFactory.getLogger(IrisGeometryCache.class).warn("Cannot cache shared Iris opaque geometry; keeping CPU submission", exception);
            }
            recordBuild(begun);
        }
        return complete;
    }

    /** Pack the union of source-index ranges. Overlapping requests share storage but remain separate draws. */
    private static Layout layout(SharedEntry entry) {
        if (entry.layout != null) return entry.layout;
        var ranges = new ArrayList<>(entry.ranges.values());
        ranges.sort(RANGE_ORDER);
        var spans = new ArrayList<Span>();
        int[] offsets = new int[ranges.size()];
        int end = -1, vertices = 0;
        for (int i = 0; i < ranges.size(); i++) {
            var range = ranges.get(i);
            int nextEnd = range.first + range.mesh.vertices;
            if (range.first > end) {
                offsets[i] = vertices;
                spans.add(new Span(range.first, range.mesh.vertices));
                vertices += range.mesh.vertices;
                end = nextEnd;
            } else {
                offsets[i] = vertices - (end - range.first);
                if (nextEnd > end) {
                    var previous = spans.getLast();
                    spans.set(spans.size() - 1, new Span(previous.first, nextEnd - previous.first));
                    vertices += nextEnd - end;
                    end = nextEnd;
                }
            }
        }
        entry.layout = new Layout(ranges, spans, offsets, vertices);
        return entry.layout;
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
                bytes -= entry.gpu.storage.bytes;
                if (entry.key.light) lightBytes -= entry.gpu.storage.bytes;
                entry.gpu.storage.close();
            }
            iterator.remove();
        }
        var shared = SHARED.values().iterator();
        while (shared.hasNext()) {
            var entry = shared.next();
            if (entry.key.mesh != mesh) continue;
            DIRTY.remove(entry);
            releaseShared(entry);
            shared.remove();
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
        entry.requestedNanos = frameNanos;
        IrisRenderStats.cache(hit, light);
        return entry.gpu;
    }

    private static void build(Entry entry) {
        long begun = System.nanoTime();
        try {
            entry.gpu = upload(entry.key);
            bytes += entry.gpu.storage.bytes;
            if (entry.key.light) lightBytes += entry.gpu.storage.bytes;
            peakBytes = Math.max(peakBytes, bytes);
            windowBytes += entry.gpu.storage.bytes;
            IrisRenderStats.upload(entry.gpu.storage.bytes, entry.key.light);
        } catch (RuntimeException exception) {
            LoggerFactory.getLogger(IrisGeometryCache.class).warn("Cannot cache Iris geometry; keeping CPU submission", exception);
        }
        recordBuild(begun);
    }

    private static void recordBuild(long begun) {
        long elapsed = System.nanoTime() - begun;
        windowNanos += elapsed;
        windowMaxBuildNanos = Math.max(windowMaxBuildNanos, elapsed);
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
        long limit = limitBytes();
        double retentionSeconds = ClientConfig.IRIS_GEOMETRY_CACHE_RETENTION_SECONDS.get();
        long idleBefore = frameNanos - (long) (retentionSeconds * 1_000_000_000L);
        if (baseBytes > limit) {
            var iterator = ENTRIES.values().iterator();
            while (baseBytes > limit && iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.gpu == null || entry.key.light || entry.requestedFrame >= frame - 1
                        || entry.requestedNanos > idleBefore) continue;
                bytes -= entry.gpu.storage.bytes;
                baseBytes -= entry.gpu.storage.bytes;
                windowBaseEvictions++;
                windowEvictedBytes += entry.gpu.storage.bytes;
                entry.gpu.storage.close();
                iterator.remove();
            }
        }
        if (baseBytes > limit) {
            var iterator = SHARED.values().iterator();
            while (baseBytes > limit && iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.storage.buffer == null || entry.requestedFrame >= frame - 1
                        || entry.requestedNanos > idleBefore) continue;
                baseBytes -= entry.storage.bytes;
                windowBaseEvictions++;
                windowEvictedBytes += entry.storage.bytes;
                DIRTY.remove(entry);
                entry.dirty = false;
                // Keep registered ranges/layout so the next use restores this source in one upload.
                releaseSharedStorage(entry);
            }
        }
        trimLights(0);
        if (System.nanoTime() - lastLog >= 5_000_000_000L) {
            if (ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean() || ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) {
                LoggerFactory.getLogger("UnrealTrainMod/Iris").info(
                        "Iris geometry cache: resident={}B lightResident={}B peak={}B softLimit={}B baseOverBudget={}B idleRetention={}s baseEvictions={} evicted={}B lightSoftLimit={}B lightOverBudget={}B lightEvictions={} pending={} builds={} upload={}B buildCpu={}ms maxBuildCpu={}ms "
                                + "| opaqueShared resident={}B buffers={} registeredRanges={} builds={} rebuilds={} upload={}B added={}B",
                        bytes, lightBytes, peakBytes, limit, Math.max(0L, bytes - lightBytes - limit),
                        retentionSeconds, windowBaseEvictions, windowEvictedBytes,
                        LIGHT_SOFT_LIMIT, Math.max(0L, lightBytes - LIGHT_SOFT_LIMIT),
                        windowLightEvictions, pending(), windowBuilds, windowBytes, windowNanos / 1_000_000.0,
                        windowMaxBuildNanos / 1_000_000.0,
                        sharedBytes, sharedBuffers, sharedRanges, sharedBuilds, sharedRebuilds, sharedUploads, sharedAdded);
            }
            lastLog = System.nanoTime();
            windowBytes = windowBuilds = windowNanos = windowLightEvictions = 0;
            windowMaxBuildNanos = windowBaseEvictions = windowEvictedBytes = 0;
            sharedBuilds = sharedRebuilds = sharedUploads = sharedAdded = 0;
        }
    }

    private static Mesh upload(Key key) {
        return new Mesh(upload(key.mesh, List.of(new Span(key.first, key.count)), key.count, key.opaque, key.light),
                0, key.count, false);
    }

    private static Storage upload(IndexedMesh mesh, List<Span> spans, int count, boolean opaque, boolean light) {
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int[] identity = IrisCompat.currentIdentity();
        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try (var storage = new ByteBufferBuilder(Math.max(256,
                Math.multiplyExact(count, IrisCompat.entityVertexSize())))) {
            IrisCompat.restoreTrackIdentity(new int[3]);
            var builder = new BufferBuilder(storage, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            var v = mesh.vertices(); var colors = mesh.colors(); var indices = mesh.indices();
            for (var span : spans) for (int i = span.first; i < span.first + span.count; i++) {
                int index = indices[i], offset = index * 8;
                int color = light ? scaleRgb(colors[index]) : colors[index];
                builder.addVertex(v[offset], v[offset + 1], v[offset + 2], opaque ? color | 0xff000000 : color,
                        v[offset + 3], v[offset + 4], OverlayTexture.NO_OVERLAY, 0,
                        v[offset + 5], v[offset + 6], v[offset + 7]);
            }
            buffer.bind();
            var data = builder.buildOrThrow();
            int size = data.vertexBuffer().remaining();
            buffer.upload(data);
            return new Storage(buffer, size);
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
            bytes -= candidate.gpu.storage.bytes;
            lightBytes -= candidate.gpu.storage.bytes;
            candidate.gpu.storage.close();
            iterator.remove();
            windowLightEvictions++;
        }
    }

    private static void releaseShared(SharedEntry entry) {
        releaseSharedStorage(entry);
        sharedRanges -= entry.ranges.size();
    }

    private static void releaseSharedStorage(SharedEntry entry) {
        if (entry.storage.buffer != null) sharedBuffers--;
        bytes -= entry.storage.bytes;
        sharedBytes -= entry.storage.bytes;
        entry.storage.close();
        entry.storage.bytes = 0;
        for (var range : entry.ranges.values()) range.mesh.firstVertex = -1;
    }

    private static int scaleRgb(int color) {
        int red = Math.round(((color >>> 16) & 0xff) * LIGHT_COLOR_SCALE);
        int green = Math.round(((color >>> 8) & 0xff) * LIGHT_COLOR_SCALE);
        int blue = Math.round((color & 0xff) * LIGHT_COLOR_SCALE);
        return color & 0xff000000 | red << 16 | green << 8 | blue;
    }

    public static void clear() {
        IrisTranslucentQueue.release();
        ENTRIES.values().forEach(entry -> { if (entry.gpu != null) entry.gpu.storage.close(); });
        ENTRIES.clear(); PENDING.clear();
        SHARED.values().forEach(entry -> entry.storage.close());
        SHARED.clear(); DIRTY.clear();
        bytes = lightBytes = peakBytes = windowBytes = windowBuilds = windowNanos = windowLightEvictions = 0;
        windowMaxBuildNanos = windowBaseEvictions = windowEvictedBytes = 0;
        sharedBytes = sharedBuilds = sharedRebuilds = sharedUploads = sharedAdded = 0;
        sharedBuffers = sharedRanges = 0;
        lastLog = System.nanoTime();
        IrisBatch.clear();
        IrisPrewarmer.clear();
    }
}
