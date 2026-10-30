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
        return create(start, end, gauge, startCurve, endCurve, startVertical, endVertical, false, false);
    }

    public static TrackGeometry create(TrackVector start, TrackVector end, double gauge,
                                       TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                       TrackVerticalCurveParameters startVertical, TrackVerticalCurveParameters endVertical,
                                       boolean startDirected, boolean endDirected) {
        if (startVertical == null && endVertical == null) {
            return base(start, end, gauge, startCurve, endCurve, false, startDirected, endDirected);
        }
        var horizontal = horizontal(start, end, gauge, startCurve, endCurve, startDirected, endDirected);
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
        return horizontal(start, end, gauge, startCurve, endCurve, false, false);
    }

    public static TrackGeometry horizontal(TrackVector start, TrackVector end, double gauge,
                                           TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                           boolean startDirected, boolean endDirected) {
        return base(start, end, gauge, startCurve, endCurve, true, startDirected, endDirected);
    }

    private static TrackGeometry base(TrackVector start, TrackVector end, double gauge,
                                      TrackCurveParameters startCurve, TrackCurveParameters endCurve, boolean flat,
                                      boolean startDirected, boolean endDirected) {
        TrackPoint a = new TrackPoint(start.x(), start.z()), b = new TrackPoint(end.x(), end.z());
        LinearGrade grade = flat ? new LinearGrade(0, 0) : new LinearGrade(start.y(), end.y());
        if (start.x() == end.x() && start.z() == end.z()) {
            throw new IllegalArgumentException("Track endpoints need horizontal separation");
        }
        return startCurve == null && endCurve == null ? new StraightTrackGeometry(a, b, grade, gauge)
                : new BezierTrackGeometry(a, control(start, end, startCurve, endCurve, true, startDirected, endDirected),
                        control(start, end, startCurve, endCurve, false, startDirected, endDirected), b, grade, gauge);
    }

    /** An automatic endpoint opposite a directed handle straightens toward that handle's control point. */
    public static TrackPoint control(TrackVector start, TrackVector end,
                                     TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                     boolean atStart, boolean startDirected, boolean endDirected) {
        var from = atStart ? start : end;
        var to = atStart ? end : start;
        var curve = atStart ? startCurve : endCurve;
        var otherCurve = atStart ? endCurve : startCurve;
        boolean directed = atStart ? startDirected : endDirected;
        boolean otherDirected = atStart ? endDirected : startDirected;
        if (curve == null && otherCurve != null && otherDirected) {
            var otherControl = control(to, from, otherCurve, true);
            // The two control-polygon edges at the automatic end are collinear, giving zero curvature there.
            return new TrackPoint(from.x() + (otherControl.x() - from.x()) / 3,
                    from.z() + (otherControl.z() - from.z()) / 3);
        }
        return control(from, to, curve, directed);
    }

    public static TrackPoint control(TrackVector from, TrackVector to, TrackCurveParameters curve) {
        return control(from, to, curve, false);
    }

    public static TrackPoint control(TrackVector from, TrackVector to, TrackCurveParameters curve, boolean directed) {
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        if (curve == null) return new TrackPoint(from.x() + dx / 3, from.z() + dz / 3);
        double radians = Math.toRadians(curve.angle());
        double x = -Math.sin(radians), z = Math.cos(radians);
        if (!directed && reverseAxis(x, z, dx, dz)) {
            x = -x;
            z = -z;
        }
        return new TrackPoint(from.x() + x * curve.length(), from.z() + z * curve.length());
    }

    /** Keep a saved tangent axis and handle length, selecting the side facing out of the existing track. */
    public static TrackCurveParameters facing(TrackCurveParameters curve, TrackVector outward) {
        double angle = Math.toRadians(curve.angle());
        return reverseAxis(-Math.sin(angle), Math.cos(angle), outward.x(), outward.z())
                ? new TrackCurveParameters(curve.angle() + 180, curve.length()) : curve;
    }

    private static boolean reverseAxis(double x, double z, double dx, double dz) {
        double dot = x * dx + z * dz;
        // At a perpendicular axis choose a consistent side, independent of the axis sign.
        return dot < -1e-12 || (Math.abs(dot) <= 1e-12 && dx * z - dz * x < 0);
    }

    public static TrackCurveParameters defaults(TrackVector from, TrackVector to, double playerYaw) {
        if (to == null) return new TrackCurveParameters(playerYaw, 1);
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        return new TrackCurveParameters(Math.toDegrees(Math.atan2(-dx, dz)),
                Math.clamp(Math.hypot(dx, dz) / 3, TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH));
    }

    /** Match the actual draft control point when opening an automatic endpoint in the editor. */
    public static TrackCurveParameters defaults(TrackVector start, TrackVector end,
                                                TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                                boolean atStart, double playerYaw, boolean startDirected, boolean endDirected) {
        var curve = atStart ? startCurve : endCurve;
        if (curve != null) return curve;
        if (end == null) return defaults(start, null, playerYaw);
        var from = atStart ? start : end;
        var control = control(start, end, startCurve, endCurve, atStart, startDirected, endDirected);
        double dx = control.x() - from.x(), dz = control.z() - from.z();
        return new TrackCurveParameters(Math.toDegrees(Math.atan2(-dx, dz)),
                Math.clamp(Math.hypot(dx, dz), TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH));
    }

    /** Oriented from the draft start toward its end, even when horizontal geometry is not yet valid. */
    public static TrackVector endpointTangent(TrackVector start, TrackVector end,
                                              TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                              boolean atStart, double fallbackYaw) {
        return endpointTangent(start, end, startCurve, endCurve, atStart, fallbackYaw, false, false);
    }

    public static TrackVector endpointTangent(TrackVector start, TrackVector end,
                                              TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                              boolean atStart, double fallbackYaw, boolean startDirected, boolean endDirected) {
        if (end != null) {
            var from = atStart ? start : end;
            var control = control(start, end, startCurve, endCurve, atStart, startDirected, endDirected);
            double sign = atStart ? 1 : -1;
            double x = (control.x() - from.x()) * sign, z = (control.z() - from.z()) * sign;
            double magnitude = Math.hypot(x, z);
            if (magnitude > 0) return new TrackVector(x / magnitude, 0, z / magnitude);
        }
        double yaw = startCurve == null ? fallbackYaw : startCurve.angle();
        return new TrackVector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
    }
}
