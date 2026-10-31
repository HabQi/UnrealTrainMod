/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Two straight, horizontal paths. Their geometric crossing is never a topology node. */
public final class DiamondCrossingGeometry {
    private DiamondCrossingGeometry() {}

    /** Also produces partial selection previews without inventing the missing endpoints. */
    public static TrackAssemblyDefinition.Geometry create(List<TrackVector> points, double gauge) {
        if (points.isEmpty() || points.size() > 4) throw new IllegalArgumentException("Invalid crossing point count");
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            if (Math.abs(point.y() - points.getFirst().y()) > 1e-6) {
                throw new IllegalArgumentException("Crossing must be horizontal");
            }
            for (int j = 0; j < i; j++) {
                var other = points.get(j);
                if (Math.hypot(point.x() - other.x(), point.z() - other.z()) < 1e-6) {
                    throw new IllegalArgumentException("Crossing endpoints must be distinct");
                }
            }
        }
        var ports = new LinkedHashMap<String, TrackAssemblyDefinition.PortGeometry>();
        var paths = new LinkedHashMap<String, List<TrackGeometry>>();
        if (points.size() >= 2) add(ports, paths, points.get(0), points.get(1), gauge, "A", "B", "line_one");
        if (points.size() == 4) {
            add(ports, paths, points.get(2), points.get(3), gauge, "C", "D", "line_two");
            var a = points.get(0); var b = points.get(1); var c = points.get(2); var d = points.get(3);
            double rx = b.x() - a.x(), rz = b.z() - a.z();
            double sx = d.x() - c.x(), sz = d.z() - c.z();
            double cross = rx * sz - rz * sx;
            if (Math.abs(cross) <= 1e-6 * Math.hypot(rx, rz) * Math.hypot(sx, sz)) {
                throw new IllegalArgumentException("Crossing paths cannot be parallel or collinear");
            }
            double cx = c.x() - a.x(), cz = c.z() - a.z();
            double t = (cx * sz - cz * sx) / cross;
            double u = (cx * rz - cz * rx) / cross;
            if (t <= 0 || t >= 1 || u <= 0 || u >= 1) {
                throw new IllegalArgumentException("Crossing must be inside both paths");
            }
        }
        return new TrackAssemblyDefinition.Geometry(ports, paths);
    }

    private static void add(Map<String, TrackAssemblyDefinition.PortGeometry> ports,
            Map<String, List<TrackGeometry>> paths, TrackVector start, TrackVector end, double gauge,
            String entrance, String exit, String path) {
        var geometry = TrackPlacementGeometry.create(start, end, gauge, null, null);
        if (geometry.getLength() > TrackPlacementGeometry.MAX_LENGTH) {
            throw new IllegalArgumentException("Crossing path exceeds 256 blocks");
        }
        double yaw = Math.toDegrees(Math.atan2(start.x() - end.x(), end.z() - start.z()));
        ports.put(entrance, new TrackAssemblyDefinition.PortGeometry(start, yaw + 180));
        ports.put(exit, new TrackAssemblyDefinition.PortGeometry(end, yaw));
        paths.put(path, List.of(geometry));
    }
}
