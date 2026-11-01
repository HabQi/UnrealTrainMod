/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.vertex.VertexBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/** Frame-local references to uploaded buffers, grouped without taking ownership of GPU resources. */
final class TrackDrawQueue {
    private record Draw(RenderType type, VertexBuffer buffer, Matrix4f modelView,
                        int source, int structure, int vertices, int triangles, int storedVertices, long chunk) {}

    private final LinkedHashMap<RenderType, List<Draw>> groups = new LinkedHashMap<>();

    /** The caller supplies one matrix snapshot per mesh, shared by all of that mesh's batches. */
    void addNative(VertexBuffer buffer, Matrix4f modelView, int structure, int vertices) {
        add(new Draw(TrackRenderTypes.OPAQUE, buffer, modelView,
                TrackRenderStats.NATIVE, structure, vertices, vertices / 2, vertices, 0L));
    }

    void addTextured(RenderType type, VertexBuffer buffer, Matrix4f modelView, int structure,
                     int vertices, int storedVertices, long chunk) {
        add(new Draw(type, buffer, modelView, TrackRenderStats.RAIL_MODEL, structure,
                vertices, vertices / 3, storedVertices, chunk));
    }

    private void add(Draw draw) {
        groups.computeIfAbsent(draw.type(), ignored -> new ArrayList<>()).add(draw);
    }

    void draw(TrackRenderState state, Matrix4f projection, TrackRenderStats stats) {
        long start = stats == null ? 0L : System.nanoTime();
        try {
            for (var group : groups.values()) {
                for (var draw : group) {
                    if (draw.source() == TrackRenderStats.NATIVE) {
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
            groups.clear();
            if (stats != null) stats.submitted(System.nanoTime() - start);
        }
    }
}
