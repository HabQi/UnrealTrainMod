/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared, directed toe handle for both horizontal turnout paths. */
public final class TurnoutPlacementGeometry {
    private TurnoutPlacementGeometry() {}

    public static TrackCurveParameters defaults(TrackVector a, TrackVector b, TrackVector c, double yaw) {
        if (b == null) return new TrackCurveParameters(yaw, 1);
        double length = Math.hypot(b.x() - a.x(), b.z() - a.z());
        if (c != null) length = Math.min(length, Math.hypot(c.x() - a.x(), c.z() - a.z()));
        return new TrackCurveParameters(Math.toDegrees(Math.atan2(a.x() - b.x(), b.z() - a.z())),
                Math.clamp(length / 3, TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH));
    }

    public static TrackCurveParameters endDefaults(TrackVector a, TrackVector end, TrackCurveParameters toe) {
        return TrackPlacementGeometry.defaults(a, end, toe, null, false, toe.angle(), true, false);
    }

    public static TrackAssemblyDefinition.Geometry create(TrackVector a, TrackVector b, TrackVector c, double gauge,
            TrackCurveParameters toe, TrackCurveParameters endB, TrackCurveParameters endC,
            boolean lockedB, boolean lockedC) {
        var ports = new LinkedHashMap<String, TrackAssemblyDefinition.PortGeometry>();
        var paths = new LinkedHashMap<String, List<TrackGeometry>>();
        ports.put("A", new TrackAssemblyDefinition.PortGeometry(a, toe.angle() + 180));
        if (b != null) add(ports, paths, "B", "straight", a, b, gauge, toe, endB, lockedB);
        if (c != null) {
            if (b != null && Math.hypot(b.x() - c.x(), b.z() - c.z()) < 1e-6) {
                throw new IllegalArgumentException("Turnout exits must be distinct");
            }
            add(ports, paths, "C", "diverging", a, c, gauge, toe, endC, lockedC);
        }
        return new TrackAssemblyDefinition.Geometry(ports, paths);
    }

    private static void add(Map<String, TrackAssemblyDefinition.PortGeometry> ports,
            Map<String, List<TrackGeometry>> paths, String port, String path, TrackVector a, TrackVector end,
            double gauge, TrackCurveParameters toe, TrackCurveParameters endCurve, boolean locked) {
        double distance = Math.hypot(end.x() - a.x(), end.z() - a.z());
        if (Math.abs(end.y() - a.y()) > 1e-6 || distance < 1e-6 || distance > TrackPlacementGeometry.MAX_LENGTH) {
            throw new IllegalArgumentException("Turnout endpoints must be distinct, horizontal and within 256 blocks");
        }
        var first = TrackPlacementGeometry.control(a, end, toe, true);
        // The external tangent points out of the turnout; its control point is on the opposite side.
        var inward = locked ? new TrackCurveParameters(endCurve.angle() + 180, endCurve.length()) : endCurve;
        var last = TrackPlacementGeometry.control(a, end, toe, inward, false, true, locked);
        var geometry = new BezierTrackGeometry(new TrackPoint(a.x(), a.z()), first, last, new TrackPoint(end.x(), end.z()),
                new LinearGrade(a.y(), a.y()), gauge);
        if (geometry.getLength() > TrackPlacementGeometry.MAX_LENGTH) throw new IllegalArgumentException("Turnout path exceeds 256 blocks");
        var tangent = geometry.sample(geometry.getLength()).tangent();
        ports.put(port, new TrackAssemblyDefinition.PortGeometry(end, Math.toDegrees(Math.atan2(-tangent.x(), tangent.z()))));
        paths.put(path, List.of(geometry));
    }
}
