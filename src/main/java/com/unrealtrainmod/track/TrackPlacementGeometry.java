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
import com.unrealtrainmod.track.geometry.VerticalTrackGeometry;
import java.util.List;

/** Shared by local editing, authoritative preview validation and the atomic graph commit. */
public final class TrackPlacementGeometry {
    public static final double MAX_LENGTH = 256;

    private TrackPlacementGeometry() {}

    public static TrackGeometry create(TrackVector start, TrackVector end, double gauge,
                                       TrackCurveParameters startCurve, TrackCurveParameters endCurve) {
        return create(start, end, gauge, startCurve, endCurve, null, null);
    }

    public static TrackGeometry create(TrackVector start, TrackVector end, double gauge,
                                       TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                       TrackVerticalCurveParameters startVertical, TrackVerticalCurveParameters endVertical) {
        if (startVertical == null && endVertical == null) return base(start, end, gauge, startCurve, endCurve, false);
        var horizontal = base(start, end, gauge, startCurve, endCurve, true);
        return withVertical(horizontal, start.y(), end.y(), startVertical, endVertical);
    }

    public static TrackGeometry withVertical(TrackGeometry horizontal, double startHeight, double endHeight,
                                              TrackVerticalCurveParameters startVertical, TrackVerticalCurveParameters endVertical) {
        var controls = verticalControls(horizontal, startHeight, endHeight, startVertical, endVertical);
        return new VerticalTrackGeometry(horizontal, startHeight, endHeight, controls.get(0), controls.get(1));
    }

    public static List<TrackPoint> verticalControls(TrackGeometry horizontal, double startHeight, double endHeight,
                                                    TrackVerticalCurveParameters startVertical,
                                                    TrackVerticalCurveParameters endVertical) {
        double h = horizontal.getLength(), average = (endHeight - startHeight) / h;
        double h0 = startVertical == null ? h / 3 : startVertical.length();
        double h1 = endVertical == null ? h / 3 : endVertical.length();
        double g0 = startVertical == null ? average : startVertical.gradeAlong(horizontal.sample(0).tangent()) / 1000;
        double g1 = endVertical == null ? average : endVertical.gradeAlong(horizontal.sample(h).tangent()) / 1000;
        return List.of(new TrackPoint(h0, startHeight + g0 * h0),
                new TrackPoint(h - h1, endHeight - g1 * h1));
    }

    public static TrackGeometry horizontal(TrackVector start, TrackVector end, double gauge,
                                           TrackCurveParameters startCurve, TrackCurveParameters endCurve) {
        return base(start, end, gauge, startCurve, endCurve, true);
    }

    private static TrackGeometry base(TrackVector start, TrackVector end, double gauge,
                                      TrackCurveParameters startCurve, TrackCurveParameters endCurve, boolean flat) {
        TrackPoint a = new TrackPoint(start.x(), start.z()), b = new TrackPoint(end.x(), end.z());
        LinearGrade grade = flat ? new LinearGrade(0, 0) : new LinearGrade(start.y(), end.y());
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

    /** Oriented from the draft start toward its end, even when horizontal geometry is not yet valid. */
    public static TrackVector endpointTangent(TrackVector start, TrackVector end,
                                              TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                              boolean atStart, double fallbackYaw) {
        if (end != null) {
            var from = atStart ? start : end;
            var to = atStart ? end : start;
            var control = control(from, to, atStart ? startCurve : endCurve);
            double sign = atStart ? 1 : -1;
            double x = (control.x() - from.x()) * sign, z = (control.z() - from.z()) * sign;
            double magnitude = Math.hypot(x, z);
            if (magnitude > 0) return new TrackVector(x / magnitude, 0, z / magnitude);
        }
        double yaw = startCurve == null ? fallbackYaw : startCurve.angle();
        return new TrackVector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
    }
}
