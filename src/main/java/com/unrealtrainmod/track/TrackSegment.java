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
    private final String modelId;
    private Profile profile;

    /** Midpoint approximation in this segment's start-to-end direction; positive radius is displayed as left/up. */
    public record Profile(double grade, double radius) {}

    TrackSegment(UUID id, UUID startNodeId, UUID endNodeId, TrackGeometry geometry, TrackChunkBounds chunkBounds, String modelId) {
        this.id = id;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
        this.geometry = geometry;
        this.chunkBounds = chunkBounds;
        this.modelId = modelId;
    }

    public UUID id() { return id; }
    public UUID startNodeId() { return startNodeId; }
    public UUID endNodeId() { return endNodeId; }
    public TrackGeometry geometry() { return geometry; }
    public String modelId() { return modelId; }
    public Profile profile() {
        if (profile == null) {
            var pose = geometry.sample(geometry.getLength() / 2);
            var tangent = pose.tangent();
            double grade = 1000 * tangent.y() / Math.hypot(tangent.x(), tangent.z());
            double curvature = pose.curvature();
            profile = new Profile(grade, Math.abs(curvature) < 1e-7 ? 0 : 1 / curvature);
        }
        return profile;
    }
    public Type type() {
        return TrackGeometryDefinition.horizontal(geometry) instanceof BezierTrackGeometry ? Type.BEZIER : Type.STRAIGHT;
    }
    /** Conservative coverage, including control-bounds false positives. */
    public boolean coversChunk(int x, int z) {
        return x >= chunkBounds.minX() && x <= chunkBounds.maxX()
                && z >= chunkBounds.minZ() && z <= chunkBounds.maxZ();
    }
    TrackChunkBounds chunkBounds() { return chunkBounds; }
}
