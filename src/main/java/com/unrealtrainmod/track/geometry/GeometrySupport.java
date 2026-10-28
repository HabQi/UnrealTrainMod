/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

final class GeometrySupport {
    private GeometrySupport() {}

    static void validateGauge(double gauge) {
        if (!Double.isFinite(gauge) || gauge <= 0) {
            throw new IllegalArgumentException("Track gauge must be finite and positive");
        }
    }

    static double length(double horizontalLength, LinearGrade grade) {
        double length = Math.hypot(horizontalLength, grade.rise());
        if (!Double.isFinite(horizontalLength) || horizontalLength <= 0 || !Double.isFinite(length)) {
            throw new IllegalArgumentException("Track must have a finite positive horizontal and total length");
        }
        return length;
    }

    static double clampDistance(double distance, double length) {
        if (!Double.isFinite(distance)) {
            throw new IllegalArgumentException("Sample distance must be finite");
        }
        return Math.clamp(distance, 0, length);
    }

    static TrackPose pose(TrackPoint point, double height, double dx, double dz,
                          double horizontalScale, double verticalScale, double curvature, double gauge) {
        double magnitude = Math.hypot(dx, dz);
        double x = dx / magnitude, z = dz / magnitude;
        return new TrackPose(new TrackVector(point.x(), height, point.z()),
                new TrackVector(x * horizontalScale, verticalScale, z * horizontalScale),
                new TrackVector(z, 0, -x),
                new TrackVector(-x * verticalScale, horizontalScale, -z * verticalScale), curvature, gauge);
    }
}
