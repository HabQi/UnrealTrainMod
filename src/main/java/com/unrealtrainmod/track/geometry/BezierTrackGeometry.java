/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

import java.util.Objects;

/** A horizontal cubic Bezier centerline with height linear in horizontal arc length. */
public final class BezierTrackGeometry implements TrackGeometry {
    private static final double MIN_RELATIVE_DERIVATIVE = 1e-12;
    private final TrackPoint p0;
    private final TrackPoint p1;
    private final TrackPoint p2;
    private final TrackPoint p3;
    private final LinearGrade grade;
    private final double gauge;
    private final double derivativeScale;
    private final double dx0, dz0, dx1, dz1, dx2, dz2;
    private final ArcLengthTable arcLengths;
    private final double length;
    private final double horizontalScale;
    private final double verticalScale;

    public BezierTrackGeometry(TrackPoint p0, TrackPoint p1, TrackPoint p2, TrackPoint p3,
                               LinearGrade grade, double gauge) {
        this.p0 = Objects.requireNonNull(p0);
        this.p1 = Objects.requireNonNull(p1);
        this.p2 = Objects.requireNonNull(p2);
        this.p3 = Objects.requireNonNull(p3);
        this.grade = Objects.requireNonNull(grade);
        GeometrySupport.validateGauge(gauge);
        this.gauge = gauge;
        double edge0 = p0.distanceTo(p1), edge1 = p1.distanceTo(p2), edge2 = p2.distanceTo(p3);
        double polygonLength = edge0 + edge1 + edge2;
        if (!Double.isFinite(polygonLength) || polygonLength <= 0) {
            throw new IllegalArgumentException("Curve must have a finite positive control polygon length");
        }
        derivativeScale = Math.max(edge0, Math.max(edge1, edge2));
        dx0 = (p1.x() - p0.x()) / derivativeScale;
        dz0 = (p1.z() - p0.z()) / derivativeScale;
        dx1 = (p2.x() - p1.x()) / derivativeScale;
        dz1 = (p2.z() - p1.z()) / derivativeScale;
        dx2 = (p3.x() - p2.x()) / derivativeScale;
        dz2 = (p3.z() - p2.z()) / derivativeScale;
        validateDerivatives();
        arcLengths = new ArcLengthTable(p0, p1, p2, p3);
        length = GeometrySupport.length(arcLengths.length(), grade);
        horizontalScale = arcLengths.length() / length;
        verticalScale = grade.rise() / length;
    }

    @Override
    public double getLength() {
        return length;
    }

    public TrackPoint control1() {
        return p1;
    }

    public TrackPoint control2() {
        return p2;
    }

    double distanceAtParameter(double parameter) {
        return arcLengths.distanceAt(parameter) / horizontalScale;
    }

    @Override
    public TrackPose sample(double distance) {
        double fraction = GeometrySupport.clampDistance(distance, length) / length;
        double t = arcLengths.parameterAt(arcLengths.length() * fraction);
        double dx = 3 * derivative(dx0, dx1, dx2, t);
        double dz = 3 * derivative(dz0, dz1, dz2, t);
        double ddx = 6 * Math.fma(t, dx2 - 2 * dx1 + dx0, dx1 - dx0);
        double ddz = 6 * Math.fma(t, dz2 - 2 * dz1 + dz0, dz1 - dz0);
        double speed = Math.hypot(dx, dz);
        // Derivatives are scaled by derivativeScale; retain the sign toward UP cross tangent.
        double curvature = ((dz / speed) * ddx - (dx / speed) * ddz) / speed / speed / derivativeScale;
        return GeometrySupport.pose(pointAt(t), grade.heightAt(fraction), dx, dz,
                horizontalScale, verticalScale, curvature, gauge);
    }

    private TrackPoint pointAt(double t) {
        if (t == 0) return p0;
        if (t == 1) return p3;
        TrackPoint a = p0.interpolate(p1, t), b = p1.interpolate(p2, t), c = p2.interpolate(p3, t);
        return a.interpolate(b, t).interpolate(b.interpolate(c, t), t);
    }

    private static double derivative(double a, double b, double c, double t) {
        if (t == 0) return a;
        if (t == 1) return c;
        double first = Math.fma(t, b - a, a);
        double second = Math.fma(t, c - b, b);
        return Math.fma(t, second - first, first);
    }

    private void validateDerivatives() {
        validateDerivativeAt(0);
        validateDerivativeAt(1);
        // An interior stationary point must be a root of either nonconstant component.
        // Solve the stronger component, then check the complete horizontal derivative there.
        double ax = dx2 - 2 * dx1 + dx0, bx = 2 * (dx1 - dx0);
        double az = dz2 - 2 * dz1 + dz0, bz = 2 * (dz1 - dz0);
        double strengthX = Math.max(Math.abs(ax), Math.max(Math.abs(bx), Math.abs(dx0)));
        double strengthZ = Math.max(Math.abs(az), Math.max(Math.abs(bz), Math.abs(dz0)));
        double a = strengthX >= strengthZ ? ax : az;
        double b = strengthX >= strengthZ ? bx : bz;
        double c = strengthX >= strengthZ ? dx0 : dz0;
        if (a == 0) {
            if (b != 0) validateDerivativeAt(-c / b);
            return;
        }
        // Checking the vertex also catches a numerically rounded double root.
        validateDerivativeAt(-b / (2 * a));
        double discriminant = Math.fma(b, b, -4 * a * c);
        if (discriminant > 0) {
            double q = -0.5 * (b + Math.copySign(Math.sqrt(discriminant), b));
            validateDerivativeAt(q / a);
            validateDerivativeAt(c / q);
        }
    }

    private void validateDerivativeAt(double t) {
        if (t < 0 || t > 1) return;
        if (Math.hypot(derivative(dx0, dx1, dx2, t), derivative(dz0, dz1, dz2, t))
                <= MIN_RELATIVE_DERIVATIVE) {
            throw new IllegalArgumentException("Curve must have a nonzero horizontal tangent throughout [0, 1]");
        }
    }
}
