/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

/** Projects a world point onto a centerline using its existing arc-length sampler. */
public final class TrackProjection {
    private static final double SAMPLE_STEP = 0.25;
    private static final double DISTANCE_TOLERANCE = 1e-4;

    private TrackProjection() {}

    public static double closestDistance(TrackGeometry geometry, TrackVector point) {
        double length = geometry.getLength();
        int steps = Math.max(1, (int)Math.ceil(length / SAMPLE_STEP));
        double bestError = Double.POSITIVE_INFINITY;
        double bestDistance = 0;
        int bestInterval = 0;
        TrackVector start = geometry.sample(0).position();
        for (int i = 0; i < steps; i++) {
            double from = length * i / steps, to = length * (i + 1) / steps;
            TrackVector end = geometry.sample(to).position();
            double dx = end.x() - start.x(), dy = end.y() - start.y(), dz = end.z() - start.z();
            double squaredLength = dx * dx + dy * dy + dz * dz;
            double fraction = squaredLength == 0 ? 0 : Math.clamp(
                    ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy
                            + (point.z() - start.z()) * dz) / squaredLength, 0, 1);
            double distance = from + fraction * (to - from);
            double error = squaredDistance(geometry.sample(distance).position(), point);
            if (error < bestError) {
                bestError = error;
                bestDistance = distance;
                bestInterval = i;
            }
            start = end;
        }

        double low = length * bestInterval / steps, high = length * (bestInterval + 1) / steps;
        // Keep endpoints as candidates: the minimum may be exactly at a track end.
        for (double distance : new double[]{low, high}) {
            double error = squaredDistance(geometry.sample(distance).position(), point);
            if (error < bestError) {
                bestError = error;
                bestDistance = distance;
            }
        }
        while (high - low > DISTANCE_TOLERANCE) {
            double first = low + (high - low) / 3, second = high - (high - low) / 3;
            double firstError = squaredDistance(geometry.sample(first).position(), point);
            double secondError = squaredDistance(geometry.sample(second).position(), point);
            if (firstError < bestError) {
                bestError = firstError;
                bestDistance = first;
            }
            if (secondError < bestError) {
                bestError = secondError;
                bestDistance = second;
            }
            if (firstError <= secondError) high = second;
            else low = first;
        }
        return bestDistance;
    }

    public static double squaredDistance(TrackVector first, TrackVector second) {
        double dx = first.x() - second.x(), dy = first.y() - second.y(), dz = first.z() - second.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
