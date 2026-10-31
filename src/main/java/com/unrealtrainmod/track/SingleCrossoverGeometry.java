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

/** Two parallel straight lines and one horizontal S-shaped crossover from A to D. */
public final class SingleCrossoverGeometry {
    private static final double PARALLEL_TOLERANCE = Math.sin(Math.toRadians(1));
    private static final double MIN_SEPARATION = 1e-3;

    private SingleCrossoverGeometry() {}

    /** Also produces partial selection previews without inventing the missing endpoints. */
    public static TrackAssemblyDefinition.Geometry create(List<TrackVector> points, double gauge) {
        if (points.isEmpty() || points.size() > 4) throw new IllegalArgumentException("Invalid crossover point count");
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            if (Math.abs(point.y() - points.getFirst().y()) > 1e-6) {
                throw new IllegalArgumentException("Crossover must be horizontal");
            }
            for (int j = 0; j < i; j++) {
                var other = points.get(j);
                if (Math.hypot(point.x() - other.x(), point.z() - other.z()) < 1e-6) {
                    throw new IllegalArgumentException("Crossover endpoints must be distinct");
                }
            }
        }
        var ports = new LinkedHashMap<String, TrackAssemblyDefinition.PortGeometry>();
        var paths = new LinkedHashMap<String, List<TrackGeometry>>();
        if (points.size() >= 2) {
            add(ports, paths, points.get(0), points.get(1), gauge, "A", "B", "first_straight");
        }
        if (points.size() == 4) {
            var a = points.get(0);
            var b = points.get(1);
            var c = points.get(2);
            var d = points.get(3);
            double abx = b.x() - a.x(), abz = b.z() - a.z();
            double cdx = d.x() - c.x(), cdz = d.z() - c.z();
            double abLength = Math.hypot(abx, abz), cdLength = Math.hypot(cdx, cdz);
            double cross = (abx * cdz - abz * cdx) / (abLength * cdLength);
            double dot = (abx * cdx + abz * cdz) / (abLength * cdLength);
            if (Math.abs(cross) > PARALLEL_TOLERANCE || dot <= 0) {
                throw new IllegalArgumentException("Crossover lines must be parallel and face the same direction");
            }
            double normalX = -abz / abLength, normalZ = abx / abLength;
            double lateral = (c.x() - a.x()) * normalX + (c.z() - a.z()) * normalZ;
            double endLateral = (d.x() - a.x()) * normalX + (d.z() - a.z()) * normalZ;
            // Signed distance is linear along C-D; both ends must stay on the same separated side of A-B.
            if (Math.min(Math.abs(lateral), Math.abs(endLateral)) < MIN_SEPARATION || lateral * endLateral <= 0) {
                throw new IllegalArgumentException("Crossover lines must remain separated without crossing or touching");
            }
            double forward = ((d.x() - a.x()) * abx + (d.z() - a.z()) * abz) / abLength;
            if (forward <= 0) throw new IllegalArgumentException("Crossover exit must lie ahead of its entrance");
            add(ports, paths, c, d, gauge, "C", "D", "second_straight");
            paths.put("crossover", List.of(crossover(a, d, abx / abLength, abz / abLength,
                    cdx / cdLength, cdz / cdLength, gauge)));
            if (paths.values().stream().flatMap(List::stream).anyMatch(
                    part -> part.getLength() > TrackPlacementGeometry.MAX_LENGTH)) {
                throw new IllegalArgumentException("Crossover path exceeds 256 blocks");
            }
        }
        return new TrackAssemblyDefinition.Geometry(ports, paths);
    }

    private static void add(Map<String, TrackAssemblyDefinition.PortGeometry> ports,
                            Map<String, List<TrackGeometry>> paths, TrackVector start, TrackVector end,
                            double gauge, String entrance, String exit, String path) {
        var geometry = TrackPlacementGeometry.create(start, end, gauge, null, null);
        if (geometry.getLength() > TrackPlacementGeometry.MAX_LENGTH) {
            throw new IllegalArgumentException("Crossover line exceeds 256 blocks");
        }
        double yaw = yaw(end.x() - start.x(), end.z() - start.z());
        ports.put(entrance, new TrackAssemblyDefinition.PortGeometry(start, yaw + 180));
        ports.put(exit, new TrackAssemblyDefinition.PortGeometry(end, yaw));
        paths.put(path, List.of(geometry));
    }

    private static TrackGeometry crossover(TrackVector start, TrackVector end,
                                           double startX, double startZ, double endX, double endZ, double gauge) {
        double forward = (end.x() - start.x()) * startX + (end.z() - start.z()) * startZ;
        double handle = Math.clamp(forward / 3, TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH);
        var a = new TrackPoint(start.x(), start.z());
        var b = new TrackPoint(start.x() + startX * handle, start.z() + startZ * handle);
        var c = new TrackPoint(end.x() - endX * handle, end.z() - endZ * handle);
        var d = new TrackPoint(end.x(), end.z());
        return new BezierTrackGeometry(a, b, c, d, new LinearGrade(start.y(), start.y()), gauge);
    }

    private static double yaw(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }
}
