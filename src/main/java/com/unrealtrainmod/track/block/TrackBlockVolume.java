/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.block;

import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.geometry.VerticalTrackGeometry;
import com.unrealtrainmod.track.persistence.TrackSavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;

/** Follows centerline cells and adds one cell on each side, with a 0.3-block bed below the rail. */
public final class TrackBlockVolume {
    private static final double BED_DEPTH = 0.3;
    private static final double EPSILON = 1e-7;

    private TrackBlockVolume() {}

    public static Map<BlockPos, Integer> create(TrackGeometry geometry) {
        double length = geometry.getLength();
        if (length > TrackPlacementGeometry.MAX_LENGTH) {
            throw new TrackGraphException(TrackGraphException.Reason.VOLUME_TOO_LARGE);
        }
        Map<BlockPos, Integer> blocks = new HashMap<>();
        int steps = Math.max(1, (int)Math.ceil(length / 0.25));
        var distances = new TreeSet<Double>();
        for (int i = 1; i <= steps; i++) distances.add(length * i / steps);
        if (geometry instanceof VerticalTrackGeometry vertical) distances.addAll(vertical.heightExtrema());
        TrackVector start = geometry.sample(0).position();
        validatePosition(start);
        double previousDistance = 0;
        for (double distance : distances) {
            TrackVector end = geometry.sample(distance).position();
            validatePosition(end);
            TrackPose middle = geometry.sample((previousDistance + distance) / 2);
            // Use the grid axis closest to the lateral direction. Absolute values keep reverse placement identical.
            int sideX = Math.abs(middle.lateral().x()) >= Math.abs(middle.lateral().z()) ? 1 : 0;
            int sideZ = 1 - sideX;
            double crossX = boundaryFraction(start.x(), end.x());
            double crossZ = boundaryFraction(start.z(), end.z());
            double first = Math.min(crossX, crossZ), last = Math.max(crossX, crossZ);
            interval(blocks, start, end, 0, first, sideX, sideZ);
            interval(blocks, start, end, first, last, sideX, sideZ);
            interval(blocks, start, end, last, 1, sideX, sideZ);
            start = end;
            previousDistance = distance;
        }
        return blocks;
    }

    private static void validatePosition(TrackVector point) {
        // Check before converting coordinates to integers or enumerating cells.
        if (Math.abs(point.x()) > 30_000_000 || Math.abs(point.z()) > 30_000_000 || Math.abs(point.y()) > 2047) {
            throw new TrackGraphException(TrackGraphException.Reason.INVALID_POSITION);
        }
    }

    /** A sampling interval is at most 0.25 blocks long, so each axis can cross at most one cell boundary. */
    private static double boundaryFraction(double start, double end) {
        int cell = (int)Math.floor(start);
        if (cell == (int)Math.floor(end)) return 1;
        double boundary = end > start ? cell + 1 : cell;
        double fraction = (boundary - start) / (end - start);
        return fraction > 0 && fraction < 1 ? fraction : 1;
    }

    private static void interval(Map<BlockPos, Integer> blocks, TrackVector start, TrackVector end,
                                 double from, double to, int sideX, int sideZ) {
        // Ignore zero-length intervals and numerical slivers at simultaneous X/Z crossings.
        if (to - from <= EPSILON) return;
        double middle = (from + to) / 2;
        int centerX = (int)Math.floor(start.x() + (end.x() - start.x()) * middle);
        int centerZ = (int)Math.floor(start.z() + (end.z() - start.z()) * middle);
        double startY = start.y() + (end.y() - start.y()) * from;
        double endY = start.y() + (end.y() - start.y()) * to;
        double minY = Math.min(startY, endY), maxY = Math.max(startY, endY);
        int bottom = (int)Math.floor(minY - BED_DEPTH + EPSILON);
        int top = (int)Math.floor(maxY - EPSILON);
        for (int y = bottom; y <= top; y++) {
            int height = Math.min(16, (int)Math.ceil((maxY - y) * 16 - EPSILON));
            for (int side = -1; side <= 1; side++) {
                blocks.merge(new BlockPos(centerX + sideX * side, y, centerZ + sideZ * side), height, Math::max);
            }
            if (blocks.size() > TrackSavedData.MAX_VOLUME_BLOCKS) {
                throw new TrackGraphException(TrackGraphException.Reason.VOLUME_TOO_LARGE);
            }
        }
    }
}
