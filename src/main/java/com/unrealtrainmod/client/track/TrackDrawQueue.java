/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import com.unrealtrainmod.client.compat.iris.IrisGeometryCache;
import com.unrealtrainmod.client.compat.iris.IrisRenderTypes;
import com.unrealtrainmod.client.compat.iris.IrisRenderStats;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/** Per-view requests; reusable native range workspaces never own a page or cached mesh. */
final class TrackDrawQueue implements AutoCloseable {
    private record Draw(RenderType type, VertexBuffer buffer, Matrix4f modelView,
                        int source, int structure, int vertices, int triangles, int storedVertices,
                        float movement, IrisGeometryCache.Mesh cached, int light,
                        TrackStaticMesh pending, TrackStaticBuffers.Range range) {}
    private final List<Draw> requests = new ArrayList<>();
    private final LinkedHashMap<RenderType, List<Draw>> groups = new LinkedHashMap<>();
    private boolean resolved, iris;
    private IntBuffer first, counts;
    private PointerBuffer offsets;
    private int capacity;

    void begin() {
        clear();
        iris = IrisCompat.active();
        resolved = false;
    }

    void addStatic(TrackStaticMesh mesh, Matrix4f modelView, int structure) {
        requests.add(new Draw(null, null, modelView, mesh.source(), structure, 0, 0, 0,
                Float.NaN, null, 0, mesh, null));
    }

    void resolve(TrackStaticBuffers buffers, TrackRenderStats stats) {
        for (var request : requests) {
            if (request.pending() == null) { addResolved(request); continue; }
            if (!buffers.enqueueRanges(request.pending(), this, request.modelView(), stats)) {
                if (stats != null) stats.pending();
            }
        }
        requests.clear();
        resolved = true;
    }

    void addStaticRange(TrackStaticBuffers.Range range, Matrix4f modelView, TrackRenderStats stats) {
        var page = range.page();
        addResolved(new Draw(page.type, page.buffer, modelView, page.source, page.structure,
                range.count(), range.count() / 3, range.storedVertices(),
                Float.NaN, null, 0, null, range));
        if (stats != null && page.source == TrackRenderStats.RAIL_MODEL
                && page.structure == TrackRenderStats.STRUCTURE_TRACK) stats.railChunkBatch(page.chunk, page.type);
    }

    void addNative(VertexBuffer buffer, Matrix4f modelView, int structure, int vertices) {
        int stored = IrisCompat.active() ? vertices / 4 * 6 : vertices;
        var type = IrisCompat.active() ? IrisRenderTypes.get(ClientModelManager.WHITE, false, false, true)
                : TrackRenderTypes.OPAQUE;
        add(new Draw(type, buffer, modelView, TrackRenderStats.NATIVE, structure,
                stored, vertices / 2, stored, Float.NaN, null, 0, null, null));
    }

    void addMorph(VertexBuffer buffer, Matrix4f modelView, int structure, int vertices, float movement) {
        add(new Draw(TrackRenderTypes.MORPH, buffer, modelView, TrackRenderStats.NATIVE, structure,
                vertices, vertices / 2, vertices, movement, null, 0, null, null));
    }

    void addCached(IrisGeometryCache.Mesh mesh, RenderType type, Matrix4f modelView, int light) {
        add(new Draw(type, null, modelView, TrackRenderStats.RAIL_MODEL, TrackRenderStats.STRUCTURE_ASSEMBLY,
                mesh.vertices, mesh.vertices / 3, mesh.vertices, Float.NaN, mesh, light, null, null));
    }

    private void add(Draw draw) {
        if (resolved) addResolved(draw);
        else requests.add(draw);
    }

    private void addResolved(Draw draw) {
        groups.computeIfAbsent(draw.type(), ignored -> new ArrayList<>()).add(draw);
    }

    void draw(TrackRenderState state, Matrix4f projection, TrackRenderStats stats) {
        long start = stats == null ? 0L : System.nanoTime();
        long irisStart = IrisRenderStats.clock();
        try {
            for (var group : groups.values()) drawList(group, state, projection, stats);
        } finally {
            IrisRenderStats.submit(irisStart);
            clear();
            if (stats != null) stats.submitted(System.nanoTime() - start);
        }
    }

    void drawPreview(TrackRenderState state, Matrix4f projection) {
        iris = false;
        draw(state, projection, null);
    }

    private void drawList(List<Draw> draws, TrackRenderState state, Matrix4f projection, TrackRenderStats stats) {
        for (int i = 0; i < draws.size();) {
            var draw = draws.get(i);
            if (draw.range() != null) {
                int end = i + 1;
                while (end < draws.size()) {
                    var next = draws.get(end);
                    if (next.range() == null || next.range().page() != draw.range().page()
                            || !next.modelView().equals(draw.modelView())) break;
                    end++;
                }
                ensure(end - i);
                first.clear(); counts.clear(); offsets.clear();
                int ranges = 0, vertices = 0, triangles = 0, stored = 0;
                for (int j = i; j < end; j++) {
                    var member = draws.get(j);
                    var range = member.range();
                    if (ranges > 0 && first.get(ranges - 1) + counts.get(ranges - 1) == range.first()) {
                        counts.put(ranges - 1, counts.get(ranges - 1) + range.count());
                    } else {
                        first.put(ranges, range.first()); counts.put(ranges, range.count()); ranges++;
                    }
                    vertices += member.vertices(); triangles += member.triangles(); stored += member.storedVertices();
                }
                first.limit(ranges); counts.limit(ranges);
                if (iris) {
                    boolean changed = IrisDrawScope.current().drawTrackRanges(draw.buffer(), first, counts,
                            draw.type(), draw.modelView(), projection);
                    IrisRenderStats.gpu(triangles);
                    if (stats != null) { if (changed) stats.renderTypeSetup(); stats.irisShaderBind(); }
                } else {
                    state.prepareParts(draw.type(), draw.modelView(), projection);
                    draw.buffer().bind();
                    draw.range().page().buffer.drawRanges(first, counts, offsets, ranges);
                }
                if (stats != null) {
                    stats.ranges(end - i, ranges, ranges > 1 ? ranges : 0);
                    stats.drawn(draw.source(), draw.structure(), 1, vertices, triangles, stored, 0);
                }
                i = end;
                continue;
            }
            if (iris) {
                boolean changed = draw.cached() != null
                        ? IrisDrawScope.current().drawCached(draw.cached(), draw.type(), draw.modelView(), projection, draw.light())
                        : IrisDrawScope.draw(draw.buffer(), draw.type(), draw.modelView(), projection);
                IrisRenderStats.gpu(draw.triangles());
                if (stats != null) { if (changed) stats.renderTypeSetup(); stats.irisShaderBind(); }
            } else {
                if (!Float.isNaN(draw.movement())) state.drawMorph(draw.buffer(), draw.modelView(), projection, draw.movement());
                else state.drawNative(draw.buffer(), draw.modelView(), projection);
            }
            if (stats != null) {
                stats.ranges(1, 1, 0);
                stats.drawn(draw.source(), draw.structure(), 1, draw.vertices(), draw.triangles(), draw.storedVertices(), 0);
            }
            i++;
        }
    }

    private void ensure(int required) {
        if (capacity >= required) return;
        if (first != null) { MemoryUtil.memFree(first); MemoryUtil.memFree(counts); MemoryUtil.memFree(offsets); }
        capacity = Math.max(16, Integer.highestOneBit(required - 1) << 1);
        first = MemoryUtil.memAllocInt(capacity);
        counts = MemoryUtil.memAllocInt(capacity);
        offsets = MemoryUtil.memAllocPointer(capacity);
    }

    void clear() {
        requests.clear(); groups.clear();
    }

    @Override public void close() {
        clear();
        if (first != null) {
            MemoryUtil.memFree(first); MemoryUtil.memFree(counts); MemoryUtil.memFree(offsets);
            first = counts = null; offsets = null; capacity = 0;
        }
    }
}
