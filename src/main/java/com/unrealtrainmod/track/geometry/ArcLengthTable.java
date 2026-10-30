/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Construction-only subdivision; sampling only searches the immutable arrays. */
final class ArcLengthTable {
    static final double TOLERANCE = 1e-4;
    static final int MAX_DEPTH = 20;
    private final double[] parameters;
    private final double[] distances;

    ArcLengthTable(TrackPoint p0, TrackPoint p1, TrackPoint p2, TrackPoint p3) {
        Builder builder = new Builder();
        builder.entries.add(new Entry(0, 0));
        // Subdivide in local coordinates so a world translation does not consume the error budget.
        builder.subdivide(new TrackPoint(0, 0), new TrackPoint(p1.x() - p0.x(), p1.z() - p0.z()),
                new TrackPoint(p2.x() - p0.x(), p2.z() - p0.z()),
                new TrackPoint(p3.x() - p0.x(), p3.z() - p0.z()), 0, 1, 0);
        int size = builder.entries.size();
        parameters = new double[size];
        distances = new double[size];
        for (int i = 0; i < size; i++) {
            Entry entry = builder.entries.get(i);
            parameters[i] = entry.parameter();
            distances[i] = entry.distance();
        }
    }

    double length() {
        return distances[distances.length - 1];
    }

    double distanceAt(double parameter) {
        if (parameter <= 0) return 0;
        if (parameter >= 1) return length();
        int found = Arrays.binarySearch(parameters, parameter);
        if (found >= 0) return distances[found];
        int upper = -found - 1, lower = upper - 1;
        double fraction = (parameter - parameters[lower]) / (parameters[upper] - parameters[lower]);
        return Math.fma(fraction, distances[upper] - distances[lower], distances[lower]);
    }

    double parameterAt(double distance) {
        if (distance <= 0) return 0;
        if (distance >= length()) return 1;
        int found = Arrays.binarySearch(distances, distance);
        if (found >= 0) return parameters[found];
        int upper = -found - 1, lower = upper - 1;
        double fraction = (distance - distances[lower]) / (distances[upper] - distances[lower]);
        return Math.fma(fraction, parameters[upper] - parameters[lower], parameters[lower]);
    }

    private record Entry(double parameter, double distance) {}

    private static final class Builder {
        private final List<Entry> entries = new ArrayList<>();
        private double distance;

        private void subdivide(TrackPoint a, TrackPoint b, TrackPoint c, TrackPoint d,
                               double t0, double t1, int depth) {
            double chord = a.distanceTo(d);
            double polygon = a.distanceTo(b) + b.distanceTo(c) + c.distanceTo(d);
            double gap = Math.max(0, polygon - chord);
            double dx = d.x() - a.x(), dz = d.z() - a.z();
            // A quadratic Bezier derivative lies in the convex hull of its three controls.
            // This bounds speed variation even for a straight, nonuniformly parameterized curve.
            double speedError = Math.max(derivativeDeviation(a, b, dx, dz),
                    Math.max(derivativeDeviation(b, c, dx, dz), derivativeDeviation(c, d, dx, dz)));
            // The true length lies between the chord and the control polygon length.
            // Distributing the length budget by parameter width bounds the sum over all leaves.
            if (gap / 2 <= TOLERANCE / 2 * (t1 - t0) && speedError <= TOLERANCE / 2) {
                double nextDistance = distance + chord + gap / 2;
                if (!Double.isFinite(nextDistance) || nextDistance <= distance) {
                    throw new IllegalArgumentException("Curve arc length cannot be represented at the requested precision");
                }
                distance = nextDistance;
                entries.add(new Entry(t1, distance));
                return;
            }
            if (depth == MAX_DEPTH) {
                throw new IllegalArgumentException("Curve cannot reach arc-length tolerance " + TOLERANCE
                        + " within subdivision depth " + MAX_DEPTH);
            }
            TrackPoint ab = a.interpolate(b, 0.5), bc = b.interpolate(c, 0.5), cd = c.interpolate(d, 0.5);
            TrackPoint abc = ab.interpolate(bc, 0.5), bcd = bc.interpolate(cd, 0.5);
            TrackPoint middle = abc.interpolate(bcd, 0.5);
            double tm = (t0 + t1) / 2;
            subdivide(a, ab, abc, middle, t0, tm, depth + 1);
            subdivide(middle, bcd, cd, d, tm, t1, depth + 1);
        }

        private static double derivativeDeviation(TrackPoint a, TrackPoint b, double dx, double dz) {
            return Math.hypot(3 * (b.x() - a.x()) - dx, 3 * (b.z() - a.z()) - dz);
        }
    }
}
