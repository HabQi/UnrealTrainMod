/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

final class ReversedTrackGeometry implements TrackGeometry {
    private final TrackGeometry original;

    ReversedTrackGeometry(TrackGeometry original) {
        this.original = original;
    }

    @Override
    public double getLength() {
        return original.getLength();
    }

    @Override
    public TrackPose sample(double distance) {
        double length = getLength();
        double clamped = GeometrySupport.clampDistance(distance, length);
        return original.sample(length - clamped).reversed();
    }

    @Override
    public TrackGeometry reversed() {
        return original;
    }
}
