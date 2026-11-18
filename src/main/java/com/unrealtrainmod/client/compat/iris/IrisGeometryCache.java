/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.track.NativeTurnoutLoading;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.ModelPreparation;
import com.unrealtrainmod.client.ModelUploads;
import com.unrealtrainmod.client.StagedVertexBuffer;
import com.unrealtrainmod.model.IndexedMesh;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
        boolean dirty, failed;
        int version;
        ModelPreparation.Ticket<Layout> pending;
        ExtendedUpload upload;
        SharedEntry(SharedKey key) { this.key = key; }
    }
    private static final class Entry {
        final Key key;
        long requestedFrame;
        long requestedNanos;
        Mesh gpu;
        boolean failed, closed;
        ExtendedUpload upload;
        NativeTurnoutLoading.Trace loading;
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
        public boolean ready() { return firstVertex >= 0 && storage.buffer != null; }
    }

    private static final LinkedHashMap<Key, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75f, true);
    private static final LinkedHashMap<SharedKey, SharedEntry> SHARED = new LinkedHashMap<>(64, 0.75f, true);
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
    public static int pending() {
        int count = 0;
        for (var entry : ENTRIES.values()) if (entry.gpu == null && !entry.failed) count++;
        for (var entry : SHARED.values()) if (entry.dirty) count++;
        return count;
    }

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
        return request(mesh, first, count, opaque, false);
    }

    /** First use queues bounded uploads; a pending vehicle never substitutes repeated CPU submissions. */
    public static Mesh requestVehicle(IndexedMesh mesh, int first, int count, boolean opaque) {
        return request(mesh, first, count, opaque, false);
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
            entry.layout = null; entry.version++;
            if (entry.pending != null) { entry.pending.close(); entry.pending = null; }
            if (entry.upload != null) { entry.upload.close(); entry.upload = null; }
            entry.dirty = false; entry.failed = false;
            sharedRanges++;
        }
        return entry;
    }

    /** Range views become ready at the frame boundary after the complete shared upload. */
    static Mesh requestVehicleOpaque(IndexedMesh mesh, int first, int count, boolean opaque) {
        var entry = registerOpaque(mesh, first, count, opaque);
        var view = entry.ranges.get(((long) first << 32) | (count & 0xffffffffL)).mesh;
        entry.requestedFrame = frame;
        entry.requestedNanos = frameNanos;
        IrisRenderStats.cache(view.ready());
        if (!view.ready() && !entry.dirty && !entry.failed) {
            entry.dirty = true;
            schedule(entry);
        }
        return view;
    }

    private static void schedule(SharedEntry entry) {
        var ranges = new ArrayList<>(entry.ranges.values());
        int version = entry.version;
        ModelUploads.enqueue(() -> {
            if (entry.version != version) return true;
            try {
                if (entry.pending == null) {
                    entry.pending = ModelPreparation.geometry(() -> layout(ranges), ignored -> (long)ranges.size() * 32, ignored -> {});
                    if (entry.pending == null) return false;
                }
                if (!entry.pending.ready()) return false;
                var layout = entry.pending.value();
                if (entry.upload == null) entry.upload = new ExtendedUpload(entry.key.mesh, layout.spans, layout.vertices, entry.key.opaque, false);
                if (!entry.upload.step()) return false;
                ModelUploads.commit(() -> {
                    if (entry.version != version) return;
                    var uploaded = entry.upload.finish(); entry.upload = null;
                    entry.pending.close(); entry.pending = null;
                    int previous = entry.storage.bytes;
                    boolean replacing = entry.storage.buffer != null;
                    entry.storage.replace(uploaded);
                    for (int i = 0; i < layout.ranges.size(); i++) layout.ranges.get(i).mesh.firstVertex = layout.offsets[i];
                    long added = (long)uploaded.bytes - previous;
                    bytes += added; sharedBytes += added;
                    if (!replacing) sharedBuffers++; else sharedRebuilds++;
                    sharedBuilds++; sharedUploads += uploaded.bytes; sharedAdded += added;
                    windowBytes += uploaded.bytes; peakBytes = Math.max(peakBytes, bytes);
                    IrisRenderStats.upload(uploaded.bytes);
                    entry.layout = layout; entry.dirty = false;
                });
            } catch (RuntimeException exception) {
                entry.failed = true; entry.dirty = false;
                if (entry.pending != null) { entry.pending.close(); entry.pending = null; }
                if (entry.upload != null) { entry.upload.close(); entry.upload = null; }
                LoggerFactory.getLogger(IrisGeometryCache.class).warn("Cannot cache shared Iris geometry; using CPU submission", exception);
            }
            return true;
        });
    }

    /** Pack the union of source-index ranges. Overlapping requests share storage but remain separate draws. */
    private static Layout layout(ArrayList<Range> ranges) {
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
        return new Layout(List.copyOf(ranges), List.copyOf(spans), offsets, vertices);
    }

    /** Static items share local geometry across hand, entity and shadow views. */
    public static Mesh requestItem(IndexedMesh mesh, int first, int count) {
        return requestStatic(mesh, first, count);
    }

    /** Static baked models and previews share the budgeted, pose-independent cache. */
    public static Mesh requestStatic(IndexedMesh mesh, int first, int count) {
        return request(mesh, first, count, false, false);
    }

    public static void releaseMesh(IndexedMesh mesh) {
        var iterator = ENTRIES.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.key.mesh != mesh) continue;
            entry.closed = true; if (entry.upload != null) entry.upload.close();
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
            releaseShared(entry);
            shared.remove();
        }
    }

    /** Lights have their own soft budget; the active working set must not fall back to CPU for lack of space. */
    public static Mesh requestVehicleLight(IndexedMesh mesh, int first, int count) {
        return request(mesh, first, count, false, true);
    }

    private static Mesh request(IndexedMesh mesh, int first, int count, boolean opaque, boolean light) {
        var key = new Key(mesh, first, count, opaque, light);
        var entry = ENTRIES.get(key);
        boolean hit = entry != null && entry.gpu != null;
        if (entry == null) {
            entry = new Entry(key);
            entry.loading = NativeTurnoutLoading.current();
            ENTRIES.put(key, entry);
            if (light) trimLights((long)count * IrisCompat.entityVertexSize());
            schedule(entry);
        }
        entry.requestedFrame = frame;
        entry.requestedNanos = frameNanos;
        IrisRenderStats.cache(hit, light);
        return entry.gpu;
    }

    private static void schedule(Entry entry) {
        ModelUploads.enqueue(() -> {
            if (entry.closed) return true;
            try {
                if (entry.upload == null) entry.upload = new ExtendedUpload(entry.key.mesh,
                        List.of(new Span(entry.key.first, entry.key.count)), entry.key.count, entry.key.opaque, entry.key.light);
                if (!entry.upload.step()) return false;
                ModelUploads.commit(() -> {
                    if (entry.closed) return;
                    entry.gpu = new Mesh(entry.upload.finish(), 0, entry.key.count, false); entry.upload = null;
                    bytes += entry.gpu.storage.bytes;
                    if (entry.key.light) lightBytes += entry.gpu.storage.bytes;
                    peakBytes = Math.max(peakBytes, bytes); windowBytes += entry.gpu.storage.bytes;
                    IrisRenderStats.upload(entry.gpu.storage.bytes, entry.key.light);
                });
            } catch (RuntimeException exception) {
                entry.failed = true;
                if (entry.upload != null) { entry.upload.close(); entry.upload = null; }
                LoggerFactory.getLogger(IrisGeometryCache.class).warn("Cannot cache Iris geometry; using CPU submission", exception);
            }
            return true;
        });
    }

    public static boolean waiting(IndexedMesh mesh, int first, int count, boolean opaque) {
        var entry = ENTRIES.get(new Key(mesh, first, count, opaque, false));
        return entry != null && entry.gpu == null && !entry.failed;
    }
    static boolean accepted(Mesh mesh, IndexedMesh source, int first, int count, boolean opaque, boolean light) {
        if (mesh != null) return mesh.ready();
        var entry = ENTRIES.get(new Key(source, first, count, opaque, light));
        return entry != null && entry.failed;
    }
    static boolean acceptedOpaque(Mesh mesh, IndexedMesh source, boolean opaque) {
        var entry = SHARED.get(new SharedKey(source, opaque));
        return mesh != null && mesh.ready() || entry != null && entry.failed;
    }

    private static void recordBuild(long begun) {
        long elapsed = System.nanoTime() - begun;
        windowNanos += elapsed;
        windowMaxBuildNanos = Math.max(windowMaxBuildNanos, elapsed);
        windowBuilds++;
    }

    /** Frame-end eviction never invalidates buffers still referenced by the current view. */
    public static void endFrame() {
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

    /** Iris extension and tangent generation stay on the render thread, at most 512 KiB per step. */
    private static final class ExtendedUpload implements AutoCloseable {
        final IndexedMesh mesh;
        final List<Span> spans;
        final int count;
        final boolean opaque, light;
        long nanos;
        StagedVertexBuffer buffer;
        int span, reference, written;
        ExtendedUpload(IndexedMesh mesh, List<Span> spans, int count, boolean opaque, boolean light) {
            this.mesh = mesh; this.spans = spans; this.count = count; this.opaque = opaque; this.light = light;
        }
        boolean step() {
            if (written == count) return true;
            int stride = IrisCompat.entityVertexSize();
            int amount = Math.min(count - written, ModelUploads.SLICE_BYTES / stride / 3 * 3);
            return ModelUploads.run(amount * stride, () -> {
                int[] identity = IrisCompat.currentIdentity();
                long packStart = System.nanoTime();
                try (var binding = new StagedVertexBuffer.Binding();
                     var storage = new ByteBufferBuilder(Math.max(256, amount * stride))) {
                    IrisCompat.restoreTrackIdentity(new int[3]);
                    if (buffer == null) {
                        buffer = new StagedVertexBuffer(count);
                        try (var tiny = new ByteBufferBuilder(3 * stride)) {
                            var builder = new BufferBuilder(tiny, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                            for (int i = 0; i < 3; i++) emit(builder, mesh.indices()[spans.getFirst().first + i]);
                            buffer.allocate(builder.buildOrThrow(), Math.multiplyExact(count, stride));
                            IrisCompat.requireEntityFormat(buffer);
                        }
                    }
                    var builder = new BufferBuilder(storage, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                    for (int i = 0; i < amount; i++) {
                        var range = spans.get(span);
                        emit(builder, mesh.indices()[range.first + reference]);
                        if (++reference == range.count) { span++; reference = 0; }
                    }
                    ModelUploads.packed(System.nanoTime() - packStart);
                    try (var data = builder.buildOrThrow()) { buffer.write(data.vertexBuffer(), written * stride); }
                    written += amount;
                } finally { IrisCompat.restoreTrackIdentity(identity); nanos += System.nanoTime() - packStart; }
            }) && written == count;
        }
        private void emit(BufferBuilder builder, int index) {
            var v = mesh.vertices(); int i = index * 8;
            int color = light ? scaleRgb(mesh.colors()[index]) : mesh.colors()[index];
            builder.addVertex(v[i], v[i + 1], v[i + 2], opaque ? color | 0xff000000 : color,
                    v[i + 3], v[i + 4], OverlayTexture.NO_OVERLAY, 0, v[i + 5], v[i + 6], v[i + 7]);
        }
        Storage finish() {
            try (var binding = new StagedVertexBuffer.Binding()) {
                buffer.bind();
                var result = new Storage(buffer, count * IrisCompat.entityVertexSize()); buffer = null;
                recordBuild(System.nanoTime() - nanos);
                return result;
            }
        }
        @Override public void close() { if (buffer != null) { buffer.close(); buffer = null; } }
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
        entry.version++;
        if (entry.pending != null) { entry.pending.close(); entry.pending = null; }
        if (entry.upload != null) { entry.upload.close(); entry.upload = null; }
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
        ENTRIES.values().forEach(entry -> { entry.closed = true; if (entry.upload != null) entry.upload.close(); if (entry.gpu != null) entry.gpu.storage.close(); });
        ENTRIES.clear();
        SHARED.values().forEach(IrisGeometryCache::releaseShared);
        SHARED.clear();
        bytes = lightBytes = peakBytes = windowBytes = windowBuilds = windowNanos = windowLightEvictions = 0;
        windowMaxBuildNanos = windowBaseEvictions = windowEvictedBytes = 0;
        sharedBytes = sharedBuilds = sharedRebuilds = sharedUploads = sharedAdded = 0;
        sharedBuffers = sharedRanges = 0;
        lastLog = System.nanoTime();
        IrisBatch.clear();
        IrisPrewarmer.clear();
    }
}
