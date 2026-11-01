/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** One segment/chunk cache entry, with budgeted static uploads and its own material pass. */
interface TrackRenderMesh extends AutoCloseable {
    AABB bounds();
    TrackVector origin();
    boolean isEmpty();
    boolean isUploaded();
    /** {@link TrackRenderStats#NATIVE} or {@link TrackRenderStats#RAIL_MODEL}, for the statistics breakdown. */
    int source();
    /** GL draw calls and submitted primitives of the last/next draw. */
    int drawCalls();
    int drawVertices();
    int drawTriangles();
    /** Vertex records in the buffers used by these draws, excluding repeated index references. */
    int storedVertices();
    /** Check for light changes and upload within budget, keeping previously uploaded buffers drawable. */
    int upload(ClientLevel level, int budget, TrackRenderStats stats);
    /** Original per-mesh path, kept as the comparison baseline. */
    void draw(Matrix4f modelView, Matrix4f projection, TrackRenderStats stats);
    /** Shared-scope path: reuses material state across consecutive meshes. */
    void draw(TrackRenderState state, Matrix4f modelView, Matrix4f projection);
    /** Defer uploaded batches; modelView is a snapshot shared only by this mesh's queued draws. */
    void enqueue(TrackDrawQueue queue, Matrix4f modelView, int structure);
    @Override void close();
}
