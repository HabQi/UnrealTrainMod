/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.TrackVector;

/** Grade in per mille along a reference horizontal direction; length is horizontal mileage. */
public record TrackVerticalCurveParameters(double grade, double length, double referenceYaw) {
    public TrackVerticalCurveParameters {
        if (!Double.isFinite(grade) || !Double.isFinite(referenceYaw) || !Double.isFinite(length)
                || length < TrackCurveParameters.MIN_LENGTH || length > TrackCurveParameters.MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid vertical curve parameters");
        }
        referenceYaw = ((referenceYaw % 360) + 540) % 360 - 180;
    }

    public double gradeAlong(TrackVector tangent) {
        double radians = Math.toRadians(referenceYaw);
        double x = -Math.sin(radians), z = Math.cos(radians);
        double dot = x * tangent.x() + z * tangent.z();
        boolean reverse = dot < -1e-12
                || (Math.abs(dot) <= 1e-12 && tangent.x() * z - tangent.z() * x < 0);
        return reverse ? -grade : grade;
    }

    public static double yaw(TrackVector tangent) {
        return Math.toDegrees(Math.atan2(-tangent.x(), tangent.z()));
    }
}
