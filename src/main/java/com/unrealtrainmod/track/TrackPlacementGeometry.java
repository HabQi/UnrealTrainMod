/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;

/** Shared by local editing, authoritative preview validation and the atomic graph commit. */
public final class TrackPlacementGeometry {
    public static final double MAX_LENGTH = 256;

    private TrackPlacementGeometry() {}

    public static TrackGeometry create(TrackVector start, TrackVector end, double gauge,
                                       TrackCurveParameters startCurve, TrackCurveParameters endCurve) {
        TrackPoint a = new TrackPoint(start.x(), start.z()), b = new TrackPoint(end.x(), end.z());
        LinearGrade grade = new LinearGrade(start.y(), end.y());
        if (start.x() == end.x() && start.z() == end.z()) {
            throw new IllegalArgumentException("Track endpoints need horizontal separation");
        }
        return startCurve == null && endCurve == null ? new StraightTrackGeometry(a, b, grade, gauge)
                : new BezierTrackGeometry(a, control(start, end, startCurve), control(end, start, endCurve), b, grade, gauge);
    }

    public static TrackPoint control(TrackVector from, TrackVector to, TrackCurveParameters curve) {
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        if (curve == null) return new TrackPoint(from.x() + dx / 3, from.z() + dz / 3);
        double radians = Math.toRadians(curve.angle());
        double x = -Math.sin(radians), z = Math.cos(radians);
        // At a perpendicular axis choose a consistent side of the chord, independent of the axis sign.
        double dot = x * dx + z * dz;
        if (dot < -1e-12 || (Math.abs(dot) <= 1e-12 && dx * z - dz * x < 0)) {
            x = -x;
            z = -z;
        }
        return new TrackPoint(from.x() + x * curve.length(), from.z() + z * curve.length());
    }

    public static TrackCurveParameters defaults(TrackVector from, TrackVector to, double playerYaw) {
        if (to == null) return new TrackCurveParameters(playerYaw, 1);
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        return new TrackCurveParameters(Math.toDegrees(Math.atan2(-dx, dz)),
                Math.clamp(Math.hypot(dx, dz) / 3, TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH));
    }
}
