/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;

/** Built-in flat, mirrored ordinary turnout. Dimensions scale with gauge. */
public enum TrackAssemblyDefinition {
    LEFT(-1), RIGHT(1);

    public static final double ANGLE = Math.atan(1.0 / 8);
    private static final List<TrackRouteDefinition> ROUTES = List.of(
            new TrackRouteDefinition("A", "B", TrackAssemblyInstance.State.STRAIGHT),
            new TrackRouteDefinition("A", "C", TrackAssemblyInstance.State.DIVERGING));
    private final int side;

    TrackAssemblyDefinition(int side) { this.side = side; }

    public List<TrackRouteDefinition> routes() {
        return ROUTES;
    }

    public record Geometry(TrackVector b, TrackVector c, TrackPoint control1, TrackPoint control2,
                           TrackGeometry straight, TrackGeometry diverging) {}

    public Geometry geometry(TrackVector origin, double yaw, double gauge) {
        double length = 32 * gauge / 1.435;
        double radius = length / Math.sin(ANGLE);
        double handle = radius * 4 / 3 * Math.tan(ANGLE / 4);
        double offset = -side * radius * (1 - Math.cos(ANGLE));
        TrackVector b = transform(origin, yaw, 0, length);
        TrackVector c = transform(origin, yaw, offset, length);
        TrackVector p1 = transform(origin, yaw, 0, handle);
        TrackVector p2 = transform(origin, yaw, offset + side * handle * Math.sin(ANGLE),
                length - handle * Math.cos(ANGLE));
        TrackPoint a = new TrackPoint(origin.x(), origin.z());
        TrackPoint c1 = new TrackPoint(p1.x(), p1.z()), c2 = new TrackPoint(p2.x(), p2.z());
        var grade = new LinearGrade(origin.y(), origin.y());
        return new Geometry(b, c, c1, c2,
                new StraightTrackGeometry(a, new TrackPoint(b.x(), b.z()), grade, gauge),
                new BezierTrackGeometry(a, c1, c2, new TrackPoint(c.x(), c.z()), grade, gauge));
    }

    public double exitYaw(double yaw) { return yaw + side * Math.toDegrees(ANGLE); }

    private static TrackVector transform(TrackVector origin, double yaw, double x, double z) {
        double angle = Math.toRadians(yaw);
        return new TrackVector(origin.x() + x * Math.cos(angle) - z * Math.sin(angle), origin.y(),
                origin.z() + x * Math.sin(angle) + z * Math.cos(angle));
    }
}
