/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

import java.util.ArrayList;
import java.util.List;

/** A planar horizontal centerline composed with a Bezier (horizontal mileage, height) profile. */
public final class VerticalTrackGeometry implements TrackGeometry {
    private final TrackGeometry horizontal;
    private final BezierTrackGeometry profile;
    private final List<Double> heightExtrema;

    public VerticalTrackGeometry(TrackGeometry horizontal, double startHeight, double endHeight,
                                 TrackPoint control1, TrackPoint control2) {
        this.horizontal = horizontal;
        double h = horizontal.getLength();
        if (control1.x() <= 0 || control2.x() >= h || control1.x() > control2.x()) {
            throw new InvalidHandlesException();
        }
        profile = new BezierTrackGeometry(new TrackPoint(0, startHeight), control1, control2,
                new TrackPoint(h, endHeight), new LinearGrade(0, 0), horizontal.sample(0).gauge());
        heightExtrema = findHeightExtrema(startHeight, control1.z(), control2.z(), endHeight);
    }

    public static final class InvalidHandlesException extends IllegalArgumentException {
        public InvalidHandlesException() { super("Vertical handles must advance without crossing"); }
    }

    public TrackGeometry horizontal() { return horizontal; }
    public TrackPoint control1() { return profile.control1(); }
    public TrackPoint control2() { return profile.control2(); }
    public List<Double> heightExtrema() { return heightExtrema; }

    /** Map a longitudinal control to the horizontal alignment, extending at an endpoint if needed. */
    public static TrackVector controlPosition(TrackGeometry horizontal, TrackPoint control) {
        double s = Math.clamp(control.x(), 0, horizontal.getLength());
        var pose = horizontal.sample(s);
        double extension = control.x() - s;
        return new TrackVector(pose.position().x() + pose.tangent().x() * extension,
                control.z(), pose.position().z() + pose.tangent().z() * extension);
    }

    @Override public double getLength() { return profile.getLength(); }

    @Override
    public TrackPose sample(double distance) {
        var vertical = profile.sample(distance);
        var flat = horizontal.sample(vertical.position().x());
        double a = vertical.tangent().x(), b = vertical.tangent().z();
        var t = flat.tangent();
        return new TrackPose(new TrackVector(flat.position().x(), vertical.position().z(), flat.position().z()),
                new TrackVector(t.x() * a, b, t.z() * a), flat.lateral(),
                new TrackVector(-t.x() * b, a, -t.z() * b), flat.curvature(), flat.gauge());
    }

    private List<Double> findHeightExtrema(double y0, double y1, double y2, double y3) {
        double d0 = y1 - y0, d1 = y2 - y1, d2 = y3 - y2;
        double scale = Math.max(Math.abs(d0), Math.max(Math.abs(d1), Math.abs(d2)));
        if (scale == 0) return List.of();
        d0 /= scale;
        d1 /= scale;
        d2 /= scale;
        double a = d2 - 2 * d1 + d0;
        double b = 2 * (d1 - d0), c = d0;
        var result = new ArrayList<Double>();
        if (a == 0) {
            if (b != 0) addExtremum(result, -c / b);
        } else {
            double discriminant = Math.fma(b, b, -4 * a * c);
            if (discriminant > 0) {
                double q = -0.5 * (b + Math.copySign(Math.sqrt(discriminant), b));
                addExtremum(result, q / a);
                addExtremum(result, c / q);
            }
        }
        result.sort(Double::compare);
        return List.copyOf(result);
    }

    private void addExtremum(List<Double> result, double t) {
        if (t > 0 && t < 1) result.add(profile.distanceAtParameter(t));
    }
}
