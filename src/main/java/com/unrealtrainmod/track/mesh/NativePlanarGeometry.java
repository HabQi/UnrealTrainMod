/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.*;

/** Preparation-only footprint unions and attribute-preserving chunk cuts. */
public final class NativePlanarGeometry {
    private NativePlanarGeometry() {}
    public record Point(double x, double z) {}
    public static Area polygon(List<Point> points) {
        var path = new Path2D.Double(); var first = points.getFirst(); path.moveTo(first.x(),first.z());
        for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i).x(),points.get(i).z());
        path.closePath(); return new Area(path);
    }
    public static Area union(List<Area> areas) {
        if (areas.isEmpty()) return new Area();
        var work = new ArrayList<>(areas);
        while (work.size() > 1) {
            var next = new ArrayList<Area>();
            for (int i = 0; i < work.size(); i += 2) { var a = new Area(work.get(i)); if (i+1 < work.size()) a.add(work.get(i+1)); next.add(a); }
            work = next;
        }
        return work.getFirst();
    }
    /** Attribute-preserving cut against a horizontal plane, used for chunk and rigid-piece boundaries. */
    public static List<float[]> clip(List<float[]> input, double nx, double nz, double boundary, boolean positive) {
        var output = new ArrayList<float[]>(); if (input.isEmpty()) return output;
        var previous = input.getLast(); double before = (previous[0]*nx+previous[2]*nz-boundary)*(positive ? 1 : -1);
        for (var current : input) {
            double after = (current[0]*nx+current[2]*nz-boundary)*(positive ? 1 : -1);
            if ((before >= 0) != (after >= 0)) {
                double t = before/(before-after); var vertex = new float[8];
                for (int i = 0; i < 8; i++) vertex[i] = (float)(previous[i]+t*(current[i]-previous[i]));
                if (nx == 1 && nz == 0) vertex[0] = (float)boundary;
                if (nx == 0 && nz == 1) vertex[2] = (float)boundary;
                output.add(vertex);
            }
            if (after >= 0) output.add(current); previous = current; before = after;
        }
        return output;
    }
}
