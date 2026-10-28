/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import java.util.UUID;

/** Immutable endpoints and geometry, created only through the graph's validated operations. */
public final class TrackSegment {
    public enum Type { STRAIGHT, BEZIER }

    private final UUID id;
    private final UUID startNodeId;
    private final UUID endNodeId;
    private final TrackGeometry geometry;
    private final TrackChunkBounds chunkBounds;

    TrackSegment(UUID id, UUID startNodeId, UUID endNodeId, TrackGeometry geometry, TrackChunkBounds chunkBounds) {
        this.id = id;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
        this.geometry = geometry;
        this.chunkBounds = chunkBounds;
    }

    public UUID id() { return id; }
    public UUID startNodeId() { return startNodeId; }
    public UUID endNodeId() { return endNodeId; }
    public TrackGeometry geometry() { return geometry; }
    public Type type() { return geometry instanceof BezierTrackGeometry ? Type.BEZIER : Type.STRAIGHT; }
    /** Conservative coverage, including control-bounds false positives. */
    public boolean coversChunk(int x, int z) {
        return x >= chunkBounds.minX() && x <= chunkBounds.maxX()
                && z >= chunkBounds.minZ() && z <= chunkBounds.maxZ();
    }
    TrackChunkBounds chunkBounds() { return chunkBounds; }
}
