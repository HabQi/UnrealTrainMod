/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

/** Immutable centerline geometry; all distances include the grade and are in blocks. */
public interface TrackGeometry {
    double getLength();

    /** Finite distances outside [0, length] are clamped to the corresponding endpoint. */
    TrackPose sample(double distance);

    /** Reuses this geometry's caches; distance s samples the original at length - s. */
    default TrackGeometry reversed() {
        return new ReversedTrackGeometry(this);
    }
}
