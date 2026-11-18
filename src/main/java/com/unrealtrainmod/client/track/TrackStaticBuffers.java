/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.ModelPreparation;
import com.unrealtrainmod.client.ModelUploads;
import com.unrealtrainmod.client.StagedVertexBuffer;
import com.mojang.blaze3d.vertex.MeshData;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisRenderStats;
import com.unrealtrainmod.client.compat.iris.IrisRenderTypes;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

/** Chunk/material pages with source-local visibility and transactional replacement of range views. */
final class TrackStaticBuffers implements AutoCloseable {
    static final TrackStaticBuffers WORLD = new TrackStaticBuffers(false);
    private static final int MAX_REFERENCES = 262_143;
    private static final int[] QUAD_INDICES = {0, 1, 2, 2, 3, 0};
    private static final int[] IRIS_CORNERS = {0, 1, 2, 0, 2, 3};

    private record Key(long chunk, RenderType type, int source, int structure, TrackStaticMesh.PageDomain domain) {}
    record Range(Page page, int first, int count, int storedVertices) {}

    static final class Page implements AutoCloseable {
        final TrackPageBuffer buffer;
        final TrackLightBuffer light;
        final RenderType type;
        final TrackVector origin;
        final long chunk;
        final int source, structure, vertexBytes, indexBytes;
        long requested;
        Page(Group group, int vertexBytes, int indexBytes,
             TrackPageBuffer buffer, TrackLightBuffer light) {
            this.buffer = buffer;
            this.light = light;
            type = group.key.type();
            origin = group.origin;
            chunk = group.key.chunk();
            source = group.key.source();
            structure = group.key.structure();
            this.vertexBytes = vertexBytes;
            this.indexBytes = indexBytes;
        }
        @Override public void close() {
            try { light.close(); }
            finally { buffer.close(); }
        }
    }

    private static final class Part {
        final TrackStaticMesh owner;
        final Group group;
        final IndexedMesh rail;
        final TrackMeshData nativeData;
        List<Range> ranges = List.of();
        long requested;
        private int normalFace = -1;
        private float nx, ny, nz;
        Part(TrackStaticMesh owner, Group group, IndexedMesh rail, TrackMeshData nativeData) {
            this.owner = owner; this.group = group; this.rail = rail; this.nativeData = nativeData;
        }
        int count() { return rail == null ? nativeData.vertexCount() / 4 * 6 : rail.indices().length; }
        int index(int reference, boolean iris) {
            return rail == null ? reference / 6 * 4 + (iris ? IRIS_CORNERS : QUAD_INDICES)[reference % 6]
                    : rail.indices()[reference];
        }
        void collect(int vertex, boolean iris, TrackVector origin, FloatArrayList output, IntArrayList colors, LongArrayList samples) {
            double x, y, z;
            float u, v;
            int color;
            if (rail != null) {
                int i = vertex * 8;
                float[] vertices = rail.vertices();
                x = vertices[i]; y = vertices[i + 1]; z = vertices[i + 2];
                u = vertices[i + 3]; v = vertices[i + 4];
                nx = vertices[i + 5]; ny = vertices[i + 6]; nz = vertices[i + 7];
                color = rail.colors()[vertex];
            } else {
                x = nativeData.x(vertex); y = nativeData.y(vertex); z = nativeData.z(vertex);
                int face = vertex / 4 * 4;
                if (face != normalFace) {
                    double ax = nativeData.x(face + 1) - nativeData.x(face);
                    double ay = nativeData.y(face + 1) - nativeData.y(face);
                    double az = nativeData.z(face + 1) - nativeData.z(face);
                    double bx = nativeData.x(face + 2) - nativeData.x(face);
                    double by = nativeData.y(face + 2) - nativeData.y(face);
                    double bz = nativeData.z(face + 2) - nativeData.z(face);
                    double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
                    double length = Math.sqrt(cx * cx + cy * cy + cz * cz);
                    nx = length == 0 ? 0 : (float)(cx / length);
                    ny = length == 0 ? 0 : (float)(cy / length);
                    nz = length == 0 ? 0 : (float)(cz / length);
                    normalFace = face;
                }
                int corner = vertex % 4;
                u = corner == 1 || corner == 2 ? 1 : 0;
                v = corner >= 2 ? 1 : 0;
                color = iris ? nativeData.baseColor(vertex) : nativeData.color(vertex);
            }
            var sourceOrigin = owner.origin();
            x += sourceOrigin.x(); y += sourceOrigin.y(); z += sourceOrigin.z();
            samples.add(TrackLighting.key(x, y, z, nx, ny, nz));
            float localX = (float)(x - origin.x()), localY = (float)y, localZ = (float)(z - origin.z());
            output.add(localX); output.add(localY); output.add(localZ); output.add(u); output.add(v);
            output.add(nx); output.add(ny); output.add(nz); colors.add(color);
        }
    }

    private static final class Group implements AutoCloseable {
        final Key key;
        final TrackVector origin;
        final List<Part> parts = new ArrayList<>();
        List<Page> pages = List.of();
        Build pending;
        boolean dirty = true, failed;
        long requested;
        Group(Key key) {
            this.key = key;
            origin = new TrackVector((double)ChunkPos.getX(key.chunk()) * 16, 0,
                    (double)ChunkPos.getZ(key.chunk()) * 16);
        }
        void changed() {
            dirty = true; failed = false;
            if (pending != null) { pending.close(); pending = null; }
        }
        @Override public void close() {
            changed();
            pages.forEach(Page::close);
            pages = List.of();
            for (var part : parts) part.ranges = List.of();
            parts.clear();
        }
    }

    /** A page's topology, transforms and native packing are produced from a fixed source snapshot. */
    private static final class PreparedPage implements AutoCloseable {
        final float[] vertices;
        final int[] colors;
        final long[] samples;
        final ByteBuffer nativeVertices, indices;
        final List<Fragment> fragments;
        final int nextPart, nextReference;
        PreparedPage(List<Part> parts, int partIndex, int reference, boolean iris, boolean textured, TrackVector origin) {
            var data = new FloatArrayList(); var colors = new IntArrayList();
            var samples = new LongArrayList(); var indices = new IntArrayList(); var fragments = new ArrayList<Fragment>();
            var remap = new Int2IntOpenHashMap(); remap.defaultReturnValue(-1);
            int references = 0;
            while (partIndex < parts.size() && references < MAX_REFERENCES) {
                var part = parts.get(partIndex);
                int count = Math.min(part.count() - reference, MAX_REFERENCES - references);
                int first = references, stored = samples.size(); remap.clear();
                for (int end = reference + count; reference < end; reference++) {
                    int original = part.index(reference, iris);
                    if (iris) part.collect(original, true, origin, data, colors, samples);
                    else {
                        int mapped = remap.get(original);
                        if (mapped == -1) {
                            mapped = samples.size(); remap.put(original, mapped);
                            part.collect(original, false, origin, data, colors, samples);
                        }
                        indices.add(mapped);
                    }
                }
                references += count;
                fragments.add(new Fragment(part, first, count, samples.size() - stored));
                if (reference == part.count()) { partIndex++; reference = 0; }
            }
            this.vertices = data.toFloatArray(); this.colors = colors.toIntArray(); this.samples = samples.toLongArray();
            this.fragments = List.copyOf(fragments); nextPart = partIndex; nextReference = reference;
            ByteBuffer packed = null, elements = null;
            try {
                if (!iris) {
                    packed = MemoryUtil.memAlloc(Math.multiplyExact(this.colors.length, textured ? 36 : 20)).order(ByteOrder.nativeOrder());
                    for (int i = 0; i < this.colors.length; i++) {
                        int v = i * 8, color = this.colors[i];
                        packed.putFloat(vertices[v]).putFloat(vertices[v + 1]).putFloat(vertices[v + 2]);
                        packed.put((byte)(color >>> 16)).put((byte)(color >>> 8)).put((byte)color).put((byte)(color >>> 24));
                        if (textured) packed.putFloat(vertices[v + 3]).putFloat(vertices[v + 4])
                                .putInt(OverlayTexture.NO_OVERLAY);
                        packed.putInt(0);
                        if (textured) packed.put(normal(vertices[v + 5])).put(normal(vertices[v + 6])).put(normal(vertices[v + 7])).put((byte)0);
                    }
                    packed.flip();
                    boolean shorts = this.colors.length <= 65_536;
                    elements = MemoryUtil.memAlloc(Math.multiplyExact(indices.size(), shorts ? 2 : 4)).order(ByteOrder.nativeOrder());
                    for (int index : indices) { if (shorts) elements.putShort((short)index); else elements.putInt(index); }
                    elements.flip();
                }
            } catch (RuntimeException | Error exception) { MemoryUtil.memFree(packed); MemoryUtil.memFree(elements); throw exception; }
            nativeVertices = packed; this.indices = elements;
        }
        private static byte normal(float value) { return (byte)(Math.clamp(value, -1f, 1f) * 127f); }
        long bytes() { return (long)vertices.length * 4 + (long)colors.length * 4 + (long)samples.length * 8
                + (nativeVertices == null ? 0 : nativeVertices.capacity() + (long)indices.capacity()); }
        @Override public void close() { MemoryUtil.memFree(nativeVertices); MemoryUtil.memFree(indices); }
    }

    private static final class PageUpload implements AutoCloseable {
        final PreparedPage prepared;
        final Group group;
        final boolean iris, fullBright;
        TrackPageBuffer buffer;
        TrackLightBuffer light;
        int phase, offset, vertexBytes;
        boolean transferred;
        PageUpload(PreparedPage prepared, Group group, boolean iris, boolean fullBright) {
            this.prepared = prepared; this.group = group; this.iris = iris; this.fullBright = fullBright;
        }
        boolean step(ClientLevel level, TrackRenderStats stats) {
            if (phase == 3) return false;
            int stride = iris ? IrisCompat.entityVertexSize() : prepared.nativeVertices.capacity() / prepared.colors.length;
            int remaining = phase == 0 ? prepared.colors.length * stride - offset : phase == 1 ? prepared.indices.capacity() - offset : 0;
            int amount = Math.min(ModelUploads.SLICE_BYTES, remaining);
            if (iris && phase == 0) amount = Math.min(prepared.colors.length - offset / stride,
                    ModelUploads.SLICE_BYTES / stride / 3 * 3) * stride;
            if (phase == 2) amount = Math.min(8192, prepared.colors.length - offset) * 4;
            final int size = amount;
            return ModelUploads.run(size, () -> {
                try (var binding = new StagedVertexBuffer.Binding()) {
                    if (buffer == null) initialize(stride);
                    if (phase == 0) {
                        if (iris) {
                            int[] identity = IrisCompat.currentIdentity();
                            long start = System.nanoTime();
                            try (var bytes = new ByteBufferBuilder(Math.max(256, size))) {
                                IrisCompat.restoreTrackIdentity(new int[3]);
                                var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                                for (int i = offset / stride; i < (offset + size) / stride; i++) emit(builder, i);
                                ModelUploads.packed(System.nanoTime() - start);
                                try (var data = builder.buildOrThrow()) { buffer.write(data.vertexBuffer(), offset); }
                            } finally { IrisCompat.restoreTrackIdentity(identity); }
                        } else buffer.write(prepared.nativeVertices.duplicate().position(offset).limit(offset + size).slice(), offset);
                        offset += size;
                        if (offset == vertexBytes) { phase = iris ? 2 : 1; offset = 0; }
                    } else if (phase == 1) {
                        buffer.writeIndices(prepared.indices.duplicate().position(offset).limit(offset + size).slice(), offset);
                        offset += size;
                        if (offset == prepared.indices.capacity()) { phase = 2; offset = 0; }
                    } else {
                        light.initial(level, offset, size / 4, stats);
                        offset += size / 4;
                        if (offset == prepared.colors.length) { phase = 3; offset = 0; }
                    }
                }
            });
        }
        private void initialize(int stride) {
            buffer = new TrackPageBuffer(iris);
            vertexBytes = Math.multiplyExact(prepared.colors.length, stride);
            if (iris) {
                int[] identity = IrisCompat.currentIdentity();
                try (var bytes = new ByteBufferBuilder(stride * 3)) {
                    IrisCompat.restoreTrackIdentity(new int[3]);
                    var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                    for (int i = 0; i < 3; i++) emit(builder, i);
                    buffer.allocate(builder.buildOrThrow(), vertexBytes);
                    IrisCompat.requireEntityFormat(buffer);
                } finally { IrisCompat.restoreTrackIdentity(identity); }
            } else {
                var format = group.key.source() == TrackRenderStats.NATIVE ? DefaultVertexFormat.POSITION_COLOR_LIGHTMAP : DefaultVertexFormat.NEW_ENTITY;
                try (var bytes = new ByteBufferBuilder(stride * 3)) {
                    var slice = MemoryUtil.memByteBuffer(bytes.reserve(stride * 3), stride * 3);
                    slice.put(prepared.nativeVertices.duplicate().limit(stride * 3));
                    buffer.allocate(new MeshData(bytes.build(), new MeshData.DrawState(format, 3, 3,
                            VertexFormat.Mode.TRIANGLES, VertexFormat.IndexType.INT)), vertexBytes);
                }
                buffer.allocateIndices(prepared.indices.capacity(), prepared.colors.length);
            }
            light = new TrackLightBuffer(fullBright); light.samples(prepared.samples); light.attach(buffer);
        }
        private void emit(BufferBuilder builder, int vertex) {
            int i = vertex * 8; var v = prepared.vertices;
            builder.addVertex(v[i], v[i + 1], v[i + 2], prepared.colors[vertex], v[i + 3], v[i + 4],
                    OverlayTexture.NO_OVERLAY, 0, v[i + 5], v[i + 6], v[i + 7]);
        }
        Page finish(TrackRenderStats stats) {
            transferred = true;
            int indexBytes = iris ? 0 : prepared.indices.capacity();
            if (stats != null) { stats.uploaded(vertexBytes, indexBytes, vertexBytes + indexBytes); stats.pageDomainUpload(group.key.domain(), vertexBytes + indexBytes); }
            if (iris) IrisRenderStats.upload(vertexBytes);
            return new Page(group, vertexBytes, indexBytes, buffer, light);
        }
        @Override public void close() {
            if (!transferred) {
                if (light != null) light.close();
                if (buffer != null) buffer.close();
            }
        }
    }

    /** Complete replacements keep their old page ranges until the next frame's atomic commit. */
    private static final class Build implements AutoCloseable {
        final List<Part> parts;
        final List<Page> pages = new ArrayList<>();
        final IdentityHashMap<Part, List<Range>> ranges = new IdentityHashMap<>();
        ModelPreparation.Ticket<PreparedPage> pending;
        PageUpload upload;
        int partIndex, reference;
        boolean committing;
        Build(Group group) { parts = List.copyOf(group.parts); }
        boolean complete() { return partIndex == parts.size() && pending == null; }
        boolean page(Group group, boolean iris, boolean fullBright, ClientLevel level, TrackRenderStats stats) {
            if (pending == null) {
                int firstPart = partIndex, firstReference = reference;
                pending = ModelPreparation.geometry(() -> new PreparedPage(parts, firstPart, firstReference, iris,
                        group.key.source() != TrackRenderStats.NATIVE, group.origin), PreparedPage::bytes, PreparedPage::close);
            }
            if (pending == null || !pending.ready()) return false;
            var data = pending.value();
            if (upload == null) upload = new PageUpload(data, group, iris, fullBright);
            if (!upload.step(level, stats)) return false;
            if (upload.phase == 3) {
                var page = upload.finish(stats); pages.add(page); upload.close(); upload = null;
                for (var fragment : data.fragments) ranges.computeIfAbsent(fragment.part(), ignored -> new ArrayList<>())
                        .add(new Range(page, fragment.first(), fragment.count(), fragment.stored()));
                partIndex = data.nextPart; reference = data.nextReference;
                pending.close(); pending = null;
            }
            return true;
        }
        void commit(Group group) {
            for (var part : parts) part.ranges = List.copyOf(ranges.get(part));
            var old = group.pages; group.pages = List.copyOf(pages); pages.clear(); old.forEach(Page::close);
        }
        @Override public void close() {
            if (pending != null) { pending.close(); pending = null; }
            if (upload != null) { upload.close(); upload = null; }
            pages.forEach(Page::close); pages.clear();
        }
    }
    private record Fragment(Part part, int first, int count, int stored) {}

    private final boolean fullBright;
    private boolean iris;
    private boolean requestedBuilds;
    private long view;
    private final IdentityHashMap<TrackStaticMesh, List<Part>> sources = new IdentityHashMap<>();
    private final LinkedHashMap<Key, Group> groups = new LinkedHashMap<>();
    private final List<Group> requestedGroups = new ArrayList<>();

    TrackStaticBuffers(boolean fullBright) { this.fullBright = fullBright; }

    void beginView() {
        boolean backend = !fullBright && IrisCompat.active();
        if (iris != backend) {
            close();
            iris = backend;
        }
        view++;
        requestedBuilds = false;
        requestedGroups.clear();
    }

    void request(TrackStaticMesh mesh, int structure) {
        if (!sources.containsKey(mesh)) mesh.register(this, structure);
        if (NativeTurnoutLoading.owner(mesh) != null) {
            if (ready(mesh)) NativeTurnoutLoading.ready(mesh); else NativeTurnoutLoading.pending(mesh);
        }
        for (var part : sources.get(mesh)) {
            part.requested = view;
            if (part.group.requested != view) {
                part.group.requested = view;
                requestedGroups.add(part.group);
            }
            requestedBuilds |= part.group.dirty;
            for (var range : part.ranges) range.page().requested = view;
        }
    }

    boolean needsPageBuild() { return requestedBuilds; }

    void add(TrackStaticMesh owner, TrackMeshData data, int structure) {
        var group = group(owner, structure, iris
                ? IrisRenderTypes.get(ClientModelManager.WHITE, false, false, true) : TrackRenderTypes.OPAQUE);
        var part = new Part(owner, group, null, data);
        group.parts.add(part); group.changed();
        sources.put(owner, List.of(part));
    }

    void add(TrackStaticMesh owner, RailTrackMeshData data, int structure) {
        var parts = new ArrayList<Part>();
        for (var batch : data.batches()) {
            if (batch.mesh().indices().length == 0) continue;
            var type = iris ? IrisRenderTypes.get(batch.texture(), batch.doubleSided(), false, false)
                    : TrackRenderTypes.textured(batch.texture(), batch.doubleSided());
            var group = group(owner, structure, type);
            var part = new Part(owner, group, batch.mesh(), null);
            group.parts.add(part); group.changed(); parts.add(part);
        }
        sources.put(owner, parts);
    }

    private Group group(TrackStaticMesh owner, int structure, RenderType type) {
        var origin = owner.origin();
        long chunk = ChunkPos.asLong((int)Math.floor(origin.x() / 16), (int)Math.floor(origin.z() / 16));
        var key = new Key(chunk, type, owner.source(), structure, owner.pageDomain());
        return groups.computeIfAbsent(key, Group::new);
    }

    int prepare(ClientLevel level, TrackWorldRenderer.BuildBudget budget, int uploads, TrackRenderStats stats) {
        for (var group : groups.values()) {
            if (group.requested != view && group.pending == null) continue;
            if (group.dirty && !group.failed) {
                if (group.pending == null) group.pending = new Build(group);
                while (!group.pending.complete() && uploads > 0 && (budget == null || budget.pageAvailable())) {
                    long start = System.nanoTime();
                    boolean progressed;
                    try { progressed = group.pending.page(group, iris, fullBright, level, stats); }
                    catch (RuntimeException exception) {
                        group.pending.close(); group.pending = null; group.failed = true;
                        org.slf4j.LoggerFactory.getLogger(TrackStaticBuffers.class).warn("Cannot prepare static track page; retaining previous ranges", exception);
                        break;
                    }
                    if (!progressed) break;
                    if (budget != null) budget.pageFinished(start);
                    uploads--;
                    if (stats != null) stats.sharedPageBuilt(!group.pages.isEmpty());
                }
                if (group.pending != null && group.pending.complete() && !group.pending.committing) {
                    var completed = group.pending; completed.committing = true;
                    ModelUploads.commit(() -> {
                        if (group.pending != completed) return;
                        completed.commit(group); group.pending = null; group.dirty = false;
                    });
                }
            }
            // Pending replacements keep their initial light stream; refresh only pages needed by this view.
            for (var page : group.pages) {
                if (page.requested != view) continue;
                if (page.light.refresh(level, stats) && uploads > 0) {
                    page.light.upload(stats, false, false);
                    uploads--;
                }
            }
        }
        return uploads;
    }

    boolean ready(TrackStaticMesh mesh) {
        var parts = sources.get(mesh);
        if (parts == null) return false;
        // Keep a source pending until all of its material ranges are ready.
        for (var part : parts) if (part.ranges.isEmpty()) return false;
        return true;
    }

    boolean enqueueRanges(TrackStaticMesh mesh, TrackDrawQueue queue, Matrix4f modelView, TrackRenderStats stats) {
        if (!ready(mesh)) { NativeTurnoutLoading.pending(mesh); return false; }
        NativeTurnoutLoading.ready(mesh);
        var parts = sources.get(mesh);
        var source = mesh.origin();
        for (var part : parts) for (var range : part.ranges) {
            var page = range.page().origin;
            // Page vertices are relative to the chunk in X/Z and already world-height in Y.
            var view = source.x() == page.x() && source.z() == page.z() ? modelView
                    : new Matrix4f(modelView).translate((float)(page.x() - source.x()), 0, (float)(page.z() - source.z()));
            queue.addStaticRange(range, view, stats, NativeTurnoutLoading.owner(mesh));
        }
        return true;
    }

    void remove(TrackStaticMesh mesh) {
        NativeTurnoutLoading.forget(mesh);
        var parts = sources.remove(mesh);
        if (parts == null) return;
        for (var part : parts) {
            var group = part.group;
            group.parts.remove(part);
            group.changed();
            if (group.parts.isEmpty()) {
                group.close();
                groups.remove(group.key);
            }
        }
    }

    void collectStats(TrackRenderStats stats) {
        if (stats == null) return;
        int count = 0;
        long bytes = 0;
        for (var group : groups.values()) {
            for (var page : group.pages) {
                count++; bytes += page.vertexBytes + page.indexBytes + page.light.byteSize();
            }
            if (group.pending != null) for (var page : group.pending.pages) {
                count++; bytes += page.vertexBytes + page.indexBytes + page.light.byteSize();
            }
        }
        stats.sharedPages(count, bytes);
    }

    @Override public void close() {
        groups.values().forEach(Group::close);
        groups.clear();
        sources.clear();
        requestedGroups.clear();
        requestedBuilds = false;
    }
}
