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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/** Frame-local references to uploaded buffers, grouped without taking ownership of GPU resources. */
final class TrackDrawQueue {
    private record Draw(RenderType type, VertexBuffer buffer, Matrix4f modelView,
                        int source, int structure, int vertices, int triangles, int storedVertices, long chunk, float movement, IrisGeometryCache.Mesh cached, int light) {}

    private final LinkedHashMap<RenderType, List<Draw>> groups = new LinkedHashMap<>();

    /** The caller supplies one matrix snapshot per mesh, shared by all of that mesh's batches. */
    void addNative(VertexBuffer buffer, Matrix4f modelView, int structure, int vertices) {
        int stored = IrisCompat.active() ? vertices / 4 * 6 : vertices;
        var type = IrisCompat.active() ? IrisRenderTypes.get(ClientModelManager.WHITE, false, false, true) : TrackRenderTypes.OPAQUE;
        add(new Draw(type, buffer, modelView,
                TrackRenderStats.NATIVE, structure, stored, vertices / 2, stored, 0L, Float.NaN, null, 0));
    }

    void addMorph(VertexBuffer buffer, Matrix4f modelView, int structure, int vertices, float movement) {
        add(new Draw(TrackRenderTypes.MORPH, buffer, modelView, TrackRenderStats.NATIVE, structure,
                vertices, vertices / 2, vertices, 0L, movement, null, 0));
    }

    void addTextured(RenderType type, VertexBuffer buffer, Matrix4f modelView, int structure,
                     int vertices, int storedVertices, long chunk) {
        add(new Draw(type, buffer, modelView, TrackRenderStats.RAIL_MODEL, structure,
                vertices, vertices / 3, storedVertices, chunk, Float.NaN, null, 0));
    }

    void addCached(IrisGeometryCache.Mesh mesh, RenderType type, Matrix4f modelView, int light) {
        add(new Draw(type, null, modelView, TrackRenderStats.RAIL_MODEL, TrackRenderStats.STRUCTURE_ASSEMBLY,
                mesh.vertices, mesh.vertices / 3, mesh.vertices, 0L, Float.NaN, mesh, light));
    }

    private void add(Draw draw) {
        groups.computeIfAbsent(draw.type(), ignored -> new ArrayList<>()).add(draw);
    }

    void draw(TrackRenderState state, Matrix4f projection, TrackRenderStats stats) {
        long start = stats == null ? 0L : System.nanoTime();
        long irisStart = IrisRenderStats.clock();
        try {
            for (var group : groups.values()) {
                for (var draw : group) {
                    if (IrisCompat.active()) {
                        boolean changed = draw.cached() != null
                                ? IrisDrawScope.current().drawCached(draw.cached(), draw.type(), draw.modelView(), projection, draw.light())
                                : IrisDrawScope.draw(draw.buffer(), draw.type(), draw.modelView(), projection);
                        IrisRenderStats.gpu(draw.triangles());
                        if (stats != null) { if (changed) stats.renderTypeSetup(); stats.irisShaderBind(); }
                    } else if (!Float.isNaN(draw.movement())) {
                        state.drawMorph(draw.buffer(), draw.modelView(), projection, draw.movement());
                    } else if (draw.source() == TrackRenderStats.NATIVE) {
                        state.drawNative(draw.buffer(), draw.modelView(), projection);
                    } else {
                        state.drawTextured(draw.type(), draw.buffer(), draw.modelView(), projection);
                    }
                    if (stats != null) {
                        stats.drawn(draw.source(), draw.structure(), 1, draw.vertices(), draw.triangles(), draw.storedVertices(), 0L);
                        if (draw.source() == TrackRenderStats.RAIL_MODEL && draw.structure() == TrackRenderStats.STRUCTURE_TRACK) {
                            stats.railChunkBatch(draw.chunk(), draw.type());
                        }
                    }
                }
            }
        } finally {
            IrisRenderStats.submit(irisStart);
            groups.clear();
            if (stats != null) stats.submitted(System.nanoTime() - start);
        }
    }
}
