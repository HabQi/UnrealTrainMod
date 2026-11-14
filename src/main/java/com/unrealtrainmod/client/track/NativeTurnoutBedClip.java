/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.track.NativeTurnoutMeshes.Corner;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.BedCut;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Bed overlap ownership and closed solid sections are baked into the ordinary static page. */
final class NativeTurnoutBedClip {
    private NativeTurnoutBedClip() {}
    private record Point(long x, long y, long z) implements Comparable<Point> {
        static Point of(Corner c) { return new Point(Math.round(c.values()[0] * 100000.0),
                Math.round(c.values()[1] * 100000.0), Math.round(c.values()[2] * 100000.0)); }
        @Override public int compareTo(Point p) {
            int c = Long.compare(x, p.x); if (c == 0) c = Long.compare(y, p.y); return c == 0 ? Long.compare(z, p.z) : c;
        }
    }
    private record Edge(Point a, Point b) {
        static Edge of(Point a, Point b) { return a.compareTo(b) < 0 ? new Edge(a, b) : new Edge(b, a); }
    }
    private record Segment(Corner a, Corner b) {}
    private static final class Output {
        final FloatArrayList vertices = new FloatArrayList();
        final IntArrayList colors = new IntArrayList(), indices = new IntArrayList();
        void triangle(Corner a, Corner b, Corner c) {
            for (var corner : List.of(a, b, c)) {
                indices.add(colors.size()); colors.add(corner.color());
                for (float v : corner.values()) vertices.add(v);
            }
        }
        IndexedMesh finish() { return new IndexedMesh(vertices.toFloatArray(), colors.toIntArray(), indices.toIntArray()); }
    }
    private static double side(Corner c, BedCut cut) { return c.values()[0] * cut.x() + c.values()[2] * cut.z() + cut.offset(); }

    static IndexedMesh clip(IndexedMesh mesh, BedCut cut) {
        var output = new Output(); var boundary = new LinkedHashMap<Edge, Segment>();
        for (int triangle = 0; triangle < mesh.indices().length; triangle += 3) {
            var input = new ArrayList<Corner>(3);
            for (int i = 0; i < 3; i++) {
                int index = mesh.indices()[triangle + i];
                input.add(new Corner(java.util.Arrays.copyOfRange(mesh.vertices(), index * 8, index * 8 + 8), mesh.colors()[index]));
            }
            var polygon = new ArrayList<Corner>(4);
            var previous = input.getLast(); double previousSide = side(previous, cut);
            for (var current : input) {
                double currentSide = side(current, cut);
                if ((currentSide >= 0) != (previousSide >= 0)) {
                    polygon.add(NativeTurnoutMeshes.interpolate(previous, current, (float)(previousSide / (previousSide - currentSide))));
                }
                if (currentSide >= 0) polygon.add(current);
                previous = current; previousSide = currentSide;
            }
            for (int i = 1; i + 1 < polygon.size(); i++) output.triangle(polygon.getFirst(), polygon.get(i), polygon.get(i + 1));
            for (int i = 0; i < polygon.size(); i++) {
                var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
                if (Math.abs(side(a, cut)) > 1e-5 || Math.abs(side(b, cut)) > 1e-5) continue;
                var pa = Point.of(a); var pb = Point.of(b); if (pa.equals(pb)) continue;
                var edge = Edge.of(pa, pb);
                if (boundary.remove(edge) == null) boundary.put(edge, new Segment(a, b));
            }
        }
        var adjacency = new LinkedHashMap<Point, List<Edge>>();
        boundary.forEach((edge, segment) -> {
            adjacency.computeIfAbsent(edge.a(), ignored -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.b(), ignored -> new ArrayList<>()).add(edge);
        });
        while (!boundary.isEmpty()) {
            var entry = boundary.entrySet().iterator().next(); var edge = entry.getKey();
            var segment = boundary.remove(edge); var start = Point.of(segment.a()); var current = Point.of(segment.b());
            var loop = new ArrayList<Corner>(); loop.add(segment.a()); loop.add(segment.b());
            while (!current.equals(start)) {
                Edge next = null;
                for (var candidate : adjacency.get(current)) if (boundary.containsKey(candidate)) { next = candidate; break; }
                if (next == null) break;
                var nextSegment = boundary.remove(next);
                var point = Point.of(nextSegment.a()).equals(current) ? nextSegment.b() : nextSegment.a();
                current = Point.of(point); loop.add(point);
            }
            if (current.equals(start)) loop.removeLast();
            else {
                // The authored terrain shells omit the deck boundary. Complete the other
                // direction, then close the section endpoints in this vertical plane.
                current = start;
                while (true) {
                    Edge next = null;
                    for (var candidate : adjacency.get(current)) if (boundary.containsKey(candidate)) { next = candidate; break; }
                    if (next == null) break;
                    var nextSegment = boundary.remove(next);
                    var point = Point.of(nextSegment.a()).equals(current) ? nextSegment.b() : nextSegment.a();
                    current = Point.of(point); loop.addFirst(point);
                }
            }
            cap(loop, cut, output);
        }
        return output.finish();
    }
    private static double u(Corner c, BedCut cut) { return c.values()[2] * cut.x() - c.values()[0] * cut.z(); }
    private static double cross(Corner a, Corner b, Corner c, BedCut cut) {
        return (u(b, cut) - u(a, cut)) * (c.values()[1] - a.values()[1])
                - (b.values()[1] - a.values()[1]) * (u(c, cut) - u(a, cut));
    }
    private record Scan(Corner a, Corner b) {
        double min(BedCut cut) { return Math.min(u(a, cut), u(b, cut)); }
        double max(BedCut cut) { return Math.max(u(a, cut), u(b, cut)); }
        double slope(BedCut cut) { return (b.values()[1] - a.values()[1]) / (u(b, cut) - u(a, cut)); }
        double height(double x, BedCut cut) { return a.values()[1] + (x - u(a, cut)) * slope(cut); }
        Corner at(double x, BedCut cut) {
            return NativeTurnoutMeshes.interpolate(a, b, (float)((x - u(a, cut)) / (u(b, cut) - u(a, cut))));
        }
    }
    private static void cap(List<Corner> polygon, BedCut cut, Output output) {
        var corners = new ArrayList<Corner>();
        for (var corner : polygon) {
            var values = corner.values().clone(); values[5] = (float)-cut.x(); values[6] = 0; values[7] = (float)-cut.z();
            corners.add(new Corner(values, corner.color()));
        }
        var scans = new ArrayList<Scan>(); var stops = new java.util.TreeSet<Double>();
        for (int i = 0; i < corners.size(); i++) {
            var a = corners.get(i); var b = corners.get((i + 1) % corners.size());
            stops.add(u(a, cut));
            if (Math.abs(u(b, cut) - u(a, cut)) > 1e-10) scans.add(new Scan(a, b));
        }
        // Split at segment crossings too: the exported terrain deck has tiny folded lips.
        for (int i = 0; i < scans.size(); i++) for (int j = i + 1; j < scans.size(); j++) {
            var a = scans.get(i); var b = scans.get(j);
            double slope = a.slope(cut) - b.slope(cut);
            if (Math.abs(slope) < 1e-10) continue;
            double x = (b.height(0, cut) - a.height(0, cut)) / slope;
            if (x > Math.max(a.min(cut), b.min(cut)) && x < Math.min(a.max(cut), b.max(cut))) stops.add(x);
        }
        var coordinates = new ArrayList<>(stops);
        for (int i = 1; i < coordinates.size(); i++) {
            double left = coordinates.get(i - 1), right = coordinates.get(i), middle = (left + right) / 2;
            if (right - left < 1e-7) continue;
            var hits = new ArrayList<Scan>();
            for (var scan : scans) if (scan.min(cut) < middle && scan.max(cut) > middle) hits.add(scan);
            hits.sort(java.util.Comparator.comparingDouble(scan -> scan.height(middle, cut)));
            for (int j = 1; j < hits.size(); j += 2) {
                var low = hits.get(j - 1); var high = hits.get(j);
                var a = low.at(left, cut); var b = low.at(right, cut); var c = high.at(right, cut); var d = high.at(left, cut);
                if (cross(a, b, c, cut) > 1e-10) output.triangle(a, b, c);
                if (cross(a, c, d, cut) > 1e-10) output.triangle(a, c, d);
            }
        }
    }
}
