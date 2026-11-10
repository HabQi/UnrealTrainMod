/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** One inward, directed handle per draft port, shared by all paths incident to that port. */
public final class AssemblyPlacementGeometry {
    private static final List<String> PORTS = List.of("A", "B", "C", "D");

    private AssemblyPlacementGeometry() {}

    public static int partner(TrackConstructionType kind, int endpoint) {
        return kind == TrackConstructionType.SIMPLE_TURNOUT ? endpoint == 0 ? 1 : 0 : endpoint ^ 1;
    }

    public static TrackAssemblyDefinition definition(TrackConstructionType kind) {
        return switch (kind) {
            case SIMPLE_TURNOUT -> TrackAssemblyDefinition.CUSTOM;
            case DIAMOND_CROSSING -> TrackAssemblyDefinition.DIAMOND_CROSSING;
            case SINGLE_CROSSOVER -> TrackAssemblyDefinition.SINGLE_CROSSOVER;
            default -> throw new IllegalArgumentException("Unimplemented structure");
        };
    }

    /** Editor values describe the actual control; display bounds do not alter automatic geometry. */
    public static List<TrackCurveParameters> defaults(TrackConstructionType kind, List<TrackVector> points,
                                                      List<TrackCurveParameters> curves, double yaw) {
        var controls = controls(kind, points, curves, yaw);
        var result = new ArrayList<TrackCurveParameters>();
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i); var control = controls.get(i);
            result.add(new TrackCurveParameters(inwardYaw(point, control), Math.clamp(
                    Math.hypot(control.x() - point.x(), control.z() - point.z()),
                    TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH)));
        }
        return result;
    }

    /** Resolve each primary line once. The crossover borrows A and D without recomputing them. */
    private static List<TrackPoint> controls(TrackConstructionType kind, List<TrackVector> points,
                                            List<TrackCurveParameters> curves, double yaw) {
        var result = new ArrayList<TrackPoint>();
        if (kind == TrackConstructionType.SIMPLE_TURNOUT) {
            var a = points.getFirst();
            var b = points.size() > 1 ? points.get(1) : null;
            var toe = curves.getFirst();
            TrackPoint control;
            if (toe == null && b != null && curves.get(1) != null) {
                control = TrackPlacementGeometry.control(a, b, null, curves.get(1), true, true, true);
            } else {
                if (toe == null) {
                    toe = TrackPlacementGeometry.defaults(a, b, yaw);
                    if (points.size() > 2) {
                        double length = Math.min(toe.length(), Math.hypot(points.get(2).x() - a.x(), points.get(2).z() - a.z()) / 3);
                        toe = new TrackCurveParameters(toe.angle(), Math.clamp(length,
                                TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH));
                    }
                }
                control = TrackPlacementGeometry.control(a, b == null ? a : b, toe, true);
            }
            result.add(control);
            for (int i = 1; i < points.size(); i++) {
                var from = points.get(i);
                result.add(curves.get(i) == null ? new TrackPoint(from.x() + (control.x() - from.x()) / 3,
                        from.z() + (control.z() - from.z()) / 3)
                        : TrackPlacementGeometry.control(from, a, curves.get(i), true));
            }
            return result;
        }
        for (int i = 0; i < points.size(); i++) {
            int other = partner(kind, i);
            var from = points.get(i);
            result.add(other < points.size()
                    ? TrackPlacementGeometry.control(from, points.get(other), curves.get(i), curves.get(other), true, true, true)
                    : TrackPlacementGeometry.control(from, from, curves.get(i) == null ? new TrackCurveParameters(yaw, 1) : curves.get(i), true));
        }
        return result;
    }

    private static double inwardYaw(TrackVector from, TrackPoint control) {
        return Math.toDegrees(Math.atan2(from.x() - control.x(), control.z() - from.z()));
    }

    /** Partial drafts remain editable even before all ports have been selected. */
    public static TrackAssemblyDefinition.Geometry create(TrackConstructionType kind, List<TrackVector> points,
                                                           double gauge, List<TrackCurveParameters> curves, double yaw) {
        if (!kind.implemented() || points.isEmpty() || points.size() > kind.pointCount() || curves.size() != points.size()) {
            throw new IllegalArgumentException("Invalid structure draft");
        }
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            if (Math.abs(point.y() - points.getFirst().y()) > 1e-6) throw new IllegalArgumentException("Structure must be horizontal");
            for (int j = 0; j < i; j++) {
                if (Math.hypot(point.x() - points.get(j).x(), point.z() - points.get(j).z()) < 1e-6) {
                    throw new IllegalArgumentException("Structure endpoints must be distinct");
                }
            }
        }
        var handles = controls(kind, points, curves, yaw);
        var ports = new LinkedHashMap<String, TrackAssemblyDefinition.PortGeometry>();
        var paths = new LinkedHashMap<String, List<TrackGeometry>>();
        for (int i = 0; i < points.size(); i++) {
            ports.put(PORTS.get(i), new TrackAssemblyDefinition.PortGeometry(points.get(i), inwardYaw(points.get(i), handles.get(i)) + 180));
        }
        for (var entry : definition(kind).routes().entrySet()) {
            int start = PORTS.indexOf(entry.getValue().entrance()), end = PORTS.indexOf(entry.getValue().exit());
            if (start >= points.size() || end >= points.size()) continue;
            var a = points.get(start); var b = points.get(end);
            var geometry = new BezierTrackGeometry(new TrackPoint(a.x(), a.z()), handles.get(start), handles.get(end),
                    new TrackPoint(b.x(), b.z()), new LinearGrade(a.y(), a.y()), gauge);
            if (geometry.getLength() > TrackPlacementGeometry.MAX_LENGTH) throw new IllegalArgumentException("Structure path exceeds 256 blocks");
            paths.put(entry.getKey(), List.of(geometry));
        }
        return new TrackAssemblyDefinition.Geometry(ports, paths);
    }

    /** Shared by new placements and saved geometry; existing straight paths remain valid. */
    public static void validate(TrackAssemblyDefinition definition, TrackAssemblyDefinition.Geometry geometry) {
        double height = geometry.ports().get("A").position().y();
        for (var port : geometry.ports().values()) {
            if (Math.abs(port.position().y() - height) > 1e-6) throw new IllegalArgumentException("Structure must be horizontal");
        }
        for (var entry : definition.routes().entrySet()) {
            var parts = geometry.paths().get(entry.getKey());
            if (parts.size() != 1) throw new IllegalArgumentException("Structure requires one geometry per path");
            var path = parts.getFirst();
            if (path.getLength() > TrackPlacementGeometry.MAX_LENGTH) throw new IllegalArgumentException("Structure path exceeds 256 blocks");
            for (boolean start : List.of(true, false)) {
                var pose = path.sample(start ? 0 : path.getLength());
                var port = geometry.ports().get(start ? entry.getValue().entrance() : entry.getValue().exit());
                double angle = Math.toRadians(port.outwardYaw());
                double dot = (start ? -1 : 1) * (-Math.sin(angle) * pose.tangent().x() + Math.cos(angle) * pose.tangent().z());
                if (!pose.position().equals(port.position()) || Math.abs(pose.tangent().y()) > 1e-6 || dot < 1 - 1e-6) {
                    throw new IllegalArgumentException("Path must meet its port position and shared horizontal tangent");
                }
            }
        }
        if (definition == TrackAssemblyDefinition.DIAMOND_CROSSING) {
            requireCrossing(geometry.paths().get("line_one").getFirst(), geometry.paths().get("line_two").getFirst());
        }
    }

    /** Check actual horizontal curves, not the chords between their ports. */
    public static void requireCrossing(TrackGeometry first, TrackGeometry second) {
        var a = flatten(first);
        var b = flatten(second);
        for (int i = 0; i + 1 < a.size(); i++) for (int j = 0; j + 1 < b.size(); j++) {
            var p = a.get(i); var q = b.get(j);
            var endA = a.get(i + 1); var endB = b.get(j + 1);
            if (Math.max(p.x(), endA.x()) < Math.min(q.x(), endB.x()) || Math.max(q.x(), endB.x()) < Math.min(p.x(), endA.x())
                    || Math.max(p.z(), endA.z()) < Math.min(q.z(), endB.z()) || Math.max(q.z(), endB.z()) < Math.min(p.z(), endA.z())) continue;
            double rx = endA.x() - p.x(), rz = endA.z() - p.z();
            double sx = b.get(j + 1).x() - q.x(), sz = b.get(j + 1).z() - q.z();
            double cross = rx * sz - rz * sx;
            if (Math.abs(cross) <= 1e-12 * Math.hypot(rx, rz) * Math.hypot(sx, sz)) continue;
            double dx = q.x() - p.x(), dz = q.z() - p.z();
            double t = (dx * sz - dz * sx) / cross, u = (dx * rz - dz * rx) / cross;
            if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) continue;
            if ((i == 0 && t <= 1e-9) || (i + 2 == a.size() && t >= 1 - 1e-9)
                    || (j == 0 && u <= 1e-9) || (j + 2 == b.size() && u >= 1 - 1e-9)) continue;
            return;
        }
        throw new IllegalArgumentException("Crossing paths need an interior intersection");
    }

    private static List<TrackPoint> flatten(TrackGeometry geometry) {
        var start = geometry.sample(0).position();
        var end = geometry.sample(geometry.getLength()).position();
        var a = new TrackPoint(start.x(), start.z()); var d = new TrackPoint(end.x(), end.z());
        var result = new ArrayList<TrackPoint>();
        result.add(a);
        if (geometry instanceof BezierTrackGeometry curve) flatten(a, curve.control1(), curve.control2(), d, 0, result);
        else result.add(d);
        return result;
    }

    private static void flatten(TrackPoint a, TrackPoint b, TrackPoint c, TrackPoint d, int depth, List<TrackPoint> result) {
        double length = distance(a, d);
        double deviation = length == 0 ? Math.max(distance(a, b), distance(a, c))
                : Math.max(Math.abs((d.x() - a.x()) * (b.z() - a.z()) - (d.z() - a.z()) * (b.x() - a.x())),
                        Math.abs((d.x() - a.x()) * (c.z() - a.z()) - (d.z() - a.z()) * (c.x() - a.x()))) / length;
        if (depth == 20 || (deviation <= 1e-4 && distance(a, b) + distance(b, c) + distance(c, d) - length <= 1e-4)) {
            result.add(d);
            return;
        }
        var ab = midpoint(a, b); var bc = midpoint(b, c); var cd = midpoint(c, d);
        var abc = midpoint(ab, bc); var bcd = midpoint(bc, cd); var middle = midpoint(abc, bcd);
        flatten(a, ab, abc, middle, depth + 1, result);
        flatten(middle, bcd, cd, d, depth + 1, result);
    }

    private static double distance(TrackPoint a, TrackPoint b) {
        return Math.hypot(b.x() - a.x(), b.z() - a.z());
    }

    private static TrackPoint midpoint(TrackPoint a, TrackPoint b) {
        return new TrackPoint(Math.fma(0.5, b.x() - a.x(), a.x()), Math.fma(0.5, b.z() - a.z(), a.z()));
    }

}
