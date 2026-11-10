/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import net.minecraft.world.phys.AABB;

/** Native quad source; the chunk material cache owns its GPU storage. */
final class TrackGpuMesh extends TrackStaticMesh {
    private final TrackMeshData data;
    private final AABB bounds;

    TrackGpuMesh(TrackMeshData data) {
        this.data = data;
        var b = data.bounds();
        bounds = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(0.001);
    }

    @Override public AABB bounds() { return bounds; }
    @Override public TrackVector origin() { return data.origin(); }
    @Override public boolean isEmpty() { return data.vertexCount() == 0; }
    @Override public int source() { return TrackRenderStats.NATIVE; }
    @Override void register(TrackStaticBuffers buffers, int structure) { buffers.add(this, data, structure); }
}
