/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import net.minecraft.world.phys.AABB;

/** Authored static rail source, independent of vehicle GPU geometry. */
final class RailTrackGpuMesh extends TrackStaticMesh {
    private final RailTrackMeshData data;
    private final AABB bounds;

    RailTrackGpuMesh(RailTrackMeshData data) {
        this.data = data;
        var b = data.bounds();
        bounds = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(0.001);
    }

    @Override public AABB bounds() { return bounds; }
    @Override public TrackVector origin() { return data.origin(); }
    @Override public boolean isEmpty() { return data.batches().isEmpty(); }
    @Override public int source() { return TrackRenderStats.RAIL_MODEL; }
    @Override void register(TrackStaticBuffers buffers, int structure) { buffers.add(this, data, structure); }
}
