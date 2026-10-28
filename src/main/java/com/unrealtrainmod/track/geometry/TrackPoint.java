/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

/** A horizontal world position, in blocks. Height belongs to the grade profile. */
public record TrackPoint(double x, double z) {
    public TrackPoint {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Track coordinates must be finite");
        }
    }

    double distanceTo(TrackPoint other) {
        return Math.hypot(other.x - x, other.z - z);
    }

    TrackPoint interpolate(TrackPoint other, double fraction) {
        if (fraction == 0) return this;
        if (fraction == 1) return other;
        return new TrackPoint(Math.fma(fraction, other.x - x, x),
                Math.fma(fraction, other.z - z, z));
    }
}
