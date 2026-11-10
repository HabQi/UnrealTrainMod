/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.geometry;

/** A sampling view, never a replacement definition for world storage. */
final class TrackGeometrySection implements TrackGeometry {
    private final TrackGeometry source;
    private final double start, length;

    TrackGeometrySection(TrackGeometry source, double start, double end) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || end > source.getLength()) {
            throw new IllegalArgumentException("Invalid track geometry interval");
        }
        this.source = source;
        this.start = start;
        length = end - start;
    }

    @Override public double getLength() { return length; }

    @Override public TrackPose sample(double distance) {
        return source.sample(start + GeometrySupport.clampDistance(distance, length));
    }
}
