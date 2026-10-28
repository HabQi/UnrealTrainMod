/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.TrackPoint;
import java.util.function.LongConsumer;

/** Conservative centerline bounds; a Bezier curve stays inside its control polygon. */
record TrackChunkBounds(int minX, int minZ, int maxX, int maxZ) {
    // Bound allocation at the external creation/load boundary, before changing the graph.
    static final int MAX_INDEXED_CHUNKS = 65_536;

    static TrackChunkBounds of(TrackPoint... points) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (TrackPoint point : points) {
            int x = chunkCoordinate(point.x()), z = chunkCoordinate(point.z());
            minX = Math.min(minX, x); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxZ = Math.max(maxZ, z);
        }
        long width = (long)maxX - minX + 1, depth = (long)maxZ - minZ + 1;
        if (width > MAX_INDEXED_CHUNKS / depth) {
            throw new TrackGraphException(TrackGraphException.Reason.INDEX_TOO_LARGE);
        }
        return new TrackChunkBounds(minX, minZ, maxX, maxZ);
    }

    private static int chunkCoordinate(double coordinate) {
        double chunk = Math.floor(coordinate / 16);
        if (chunk < Integer.MIN_VALUE || chunk > Integer.MAX_VALUE) {
            throw new TrackGraphException(TrackGraphException.Reason.INDEX_RANGE);
        }
        return (int)chunk;
    }

    static long key(int x, int z) {
        return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32);
    }

    void forEachChunk(LongConsumer action) {
        // Use long counters so an endpoint at Integer.MAX_VALUE cannot wrap the loop.
        for (long x = minX; x <= maxX; x++) {
            for (long z = minZ; z <= maxZ; z++) action.accept(key((int)x, (int)z));
        }
    }
}
