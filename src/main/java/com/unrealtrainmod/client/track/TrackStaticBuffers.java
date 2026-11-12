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
import com.unrealtrainmod.client.ClientModelManager;
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

    private record Key(long chunk, RenderType type, int source, int structure) {}
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
        void emit(BufferBuilder builder, int vertex, boolean iris, TrackVector origin, LongArrayList samples) {
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
            if (rail != null || iris) {
                builder.addVertex(localX, localY, localZ, color, u, v, OverlayTexture.NO_OVERLAY, 0, nx, ny, nz);
            } else builder.addVertex(localX, localY, localZ).setColor(color).setLight(0);
        }
    }

    private static final class Group implements AutoCloseable {
        final Key key;
        final TrackVector origin;
        final List<Part> parts = new ArrayList<>();
        List<Page> pages = List.of();
        Build pending;
        boolean dirty = true;
        long requested;
        Group(Key key) {
            this.key = key;
            origin = new TrackVector((double)ChunkPos.getX(key.chunk()) * 16, 0,
                    (double)ChunkPos.getZ(key.chunk()) * 16);
        }
        void changed() {
            dirty = true;
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

    /** Builds complete pages incrementally; old pages remain usable until every replacement page is ready. */
    private static final class Build implements AutoCloseable {
        final List<Part> parts;
        final List<Page> pages = new ArrayList<>();
        final IdentityHashMap<Part, List<Range>> ranges = new IdentityHashMap<>();
        int partIndex, reference;
        Build(Group group) { parts = List.copyOf(group.parts); }
        boolean complete() { return partIndex == parts.size(); }

        void page(Group group, boolean iris, boolean fullBright, ClientLevel level, TrackRenderStats stats) {
            var format = group.key.source() == TrackRenderStats.NATIVE && !iris
                    ? DefaultVertexFormat.POSITION_COLOR_LIGHTMAP : DefaultVertexFormat.NEW_ENTITY;
            var indices = new IntArrayList();
            var samples = new LongArrayList();
            var fragments = new ArrayList<Fragment>();
            int references = 0, pageReferences = 0;
            for (int i = partIndex; i < parts.size() && pageReferences < MAX_REFERENCES; i++) {
                int remaining = parts.get(i).count() - (i == partIndex ? reference : 0);
                pageReferences += Math.min(remaining, MAX_REFERENCES - pageReferences);
            }
            long packStart = stats == null ? 0 : System.nanoTime();
            try (var bytes = new ByteBufferBuilder(Math.max(256, pageReferences * format.getVertexSize()))) {
                var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, format);
                var remap = new Int2IntOpenHashMap();
                remap.defaultReturnValue(-1);
                while (!complete() && references < MAX_REFERENCES) {
                    var part = parts.get(partIndex);
                    int count = Math.min(part.count() - reference, MAX_REFERENCES - references);
                    int first = references, stored = samples.size();
                    remap.clear();
                    for (int end = reference + count; reference < end; reference++) {
                        int original = part.index(reference, iris);
                        if (iris) part.emit(builder, original, true, group.origin, samples);
                        else {
                            int mapped = remap.get(original);
                            if (mapped == -1) {
                                mapped = samples.size();
                                remap.put(original, mapped);
                                part.emit(builder, original, false, group.origin, samples);
                            }
                            indices.add(mapped);
                        }
                    }
                    references += count;
                    fragments.add(new Fragment(part, first, count, samples.size() - stored));
                    if (reference == part.count()) { partIndex++; reference = 0; }
                }
                try (var mesh = builder.buildOrThrow()) {
                    var buffer = new TrackPageBuffer(iris);
                    var light = new TrackLightBuffer(fullBright);
                    try {
                        int vertexBytes = mesh.vertexBuffer().remaining();
                        light.samples(samples.toLongArray());
                        if (stats != null) stats.packed(System.nanoTime() - packStart);
                        long uploadStart = stats == null ? 0 : System.nanoTime();
                        // upload() consumes MeshData, including on failure.
                        buffer.bind();
                        buffer.upload(mesh);
                        int indexBytes = iris ? 0 : buffer.uploadIndices(indices.toIntArray(), samples.size());
                        light.attach(buffer);
                        if (stats != null) {
                            stats.gpuUpload(System.nanoTime() - uploadStart);
                            stats.uploaded(vertexBytes, indexBytes, vertexBytes + indexBytes);
                        }
                        light.refresh(level, stats);
                        light.upload(stats, true, false);
                        if (iris) IrisRenderStats.upload(vertexBytes);
                        var page = new Page(group, vertexBytes, indexBytes, buffer, light);
                        pages.add(page);
                        for (var fragment : fragments) ranges.computeIfAbsent(fragment.part(), ignored -> new ArrayList<>())
                                .add(new Range(page, fragment.first(), fragment.count(), fragment.stored()));
                    } catch (RuntimeException | Error exception) {
                        try { light.close(); } finally { buffer.close(); }
                        throw exception;
                    }
                }
            } finally { VertexBuffer.unbind(); }
        }

        void commit(Group group) {
            for (var part : parts) part.ranges = List.copyOf(ranges.get(part));
            var old = group.pages;
            group.pages = List.copyOf(pages);
            pages.clear();
            old.forEach(Page::close);
        }

        @Override public void close() { pages.forEach(Page::close); pages.clear(); }
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
        var key = new Key(chunk, type, owner.source(), structure);
        return groups.computeIfAbsent(key, Group::new);
    }

    int prepare(ClientLevel level, TrackWorldRenderer.BuildBudget budget, int uploads, TrackRenderStats stats) {
        for (var group : requestedGroups) {
            if (group.dirty) {
                if (group.pending == null) group.pending = new Build(group);
                while (!group.pending.complete() && uploads > 0 && (budget == null || budget.pageAvailable())) {
                    long start = System.nanoTime();
                    try { group.pending.page(group, iris, fullBright, level, stats); }
                    catch (RuntimeException | Error exception) { group.changed(); throw exception; }
                    finally { if (budget != null) budget.pageFinished(start); }
                    uploads--;
                    if (stats != null) stats.sharedPageBuilt(!group.pages.isEmpty());
                }
                if (group.pending.complete()) {
                    group.pending.commit(group);
                    group.pending = null;
                    group.dirty = false;
                    for (var part : group.parts) if (part.requested == view) {
                        for (var range : part.ranges) range.page().requested = view;
                    }
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
        if (!ready(mesh)) return false;
        var parts = sources.get(mesh);
        var source = mesh.origin();
        for (var part : parts) for (var range : part.ranges) {
            var page = range.page().origin;
            // Page vertices are relative to the chunk in X/Z and already world-height in Y.
            var view = source.x() == page.x() && source.z() == page.z() ? modelView
                    : new Matrix4f(modelView).translate((float)(page.x() - source.x()), 0, (float)(page.z() - source.z()));
            queue.addStaticRange(range, view, stats);
        }
        return true;
    }

    void remove(TrackStaticMesh mesh) {
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
