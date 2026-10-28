/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.Arrays;

/** Chunk-owned primitives use global distance grids, so adjacent chunks never duplicate a face. */
public final class TrackMeshBuilder {
    private static final double RAIL_STEP = 0.5;
    public static final double RAIL_WIDTH = 0.07;
    public static final double RAIL_HEIGHT = 0.15;
    private static final double TIE_SPACING = 0.6;
    private static final double FIRST_TIE = 0.3;
    private static final double TIE_WIDTH = 0.22;
    private static final double TIE_HEIGHT = 0.14;
    private static final int RAIL_COLOR = 0xffa8adb3, TIE_COLOR = 0xff765035;
    private static final int MAX_VERTICES = 262_144;

    private final TrackGeometry geometry;
    private final int chunkX, chunkZ;
    private final double length;
    private final long railCount;
    private final Builder mesh;

    private TrackMeshBuilder(TrackGeometry geometry, int chunkX, int chunkZ) {
        this.geometry = geometry;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        length = geometry.getLength();
        if (length / RAIL_STEP >= Long.MAX_VALUE) throw new IllegalArgumentException("Track is too long to mesh");
        railCount = (long)Math.ceil(length / RAIL_STEP);
        mesh = new Builder((double)chunkX * 16, (double)chunkZ * 16);
    }

    public static TrackMeshData build(TrackGeometry geometry, int chunkX, int chunkZ) {
        TrackMeshBuilder builder = new TrackMeshBuilder(geometry, chunkX, chunkZ);
        builder.rails(0, builder.railCount);
        if (builder.length > FIRST_TIE) {
            long ties = (long)Math.ceil((builder.length - FIRST_TIE) / TIE_SPACING);
            builder.ties(0, ties);
        }
        return builder.mesh.finish();
    }

    private void rails(long first, long end) {
        double a = first * RAIL_STEP, b = end == railCount ? length : end * RAIL_STEP;
        TrackPose middle = geometry.sample(a + (b - a) / 2);
        if (!mayReachChunk(middle.position(), (b - a) / 2)) return;
        if (end - first > 1) {
            long split = first + (end - first) / 2;
            rails(first, split);
            rails(split, end);
            return;
        }
        if (!owns(middle.position())) return;
        TrackPose start = geometry.sample(a), finish = geometry.sample(b);
        for (int side = -1; side <= 1; side += 2) {
            double offset = side * (middle.gauge() + RAIL_WIDTH) / 2;
            mesh.box(section(start, offset, RAIL_WIDTH / 2, -RAIL_HEIGHT, 0, 0),
                    section(finish, offset, RAIL_WIDTH / 2, -RAIL_HEIGHT, 0, 0), RAIL_COLOR,
                    first == 0, end == railCount);
        }
    }

    private void ties(long first, long end) {
        double a = FIRST_TIE + first * TIE_SPACING, b = FIRST_TIE + (end - 1) * TIE_SPACING;
        TrackPose middle = geometry.sample(a + (b - a) / 2);
        if (!mayReachChunk(middle.position(), (b - a) / 2)) return;
        if (end - first > 1) {
            long split = first + (end - first) / 2;
            ties(first, split);
            ties(split, end);
            return;
        }
        if (a >= length || !owns(middle.position())) return;
        double halfLength = (middle.gauge() + 0.9) / 2;
        mesh.box(section(middle, 0, halfLength, -RAIL_HEIGHT - TIE_HEIGHT, -RAIL_HEIGHT, -TIE_WIDTH / 2),
                section(middle, 0, halfLength, -RAIL_HEIGHT - TIE_HEIGHT, -RAIL_HEIGHT, TIE_WIDTH / 2),
                TIE_COLOR, true, true);
    }

    private boolean mayReachChunk(TrackVector middle, double radius) {
        // Any point in the interval is at most half its arc length from its midpoint.
        // Include the geometry table's numerical error; ownership is determined by the centerline.
        radius += 0.001;
        double x = (double)chunkX * 16, z = (double)chunkZ * 16;
        return middle.x() + radius >= x && middle.x() - radius < x + 16
                && middle.z() + radius >= z && middle.z() - radius < z + 16;
    }

    private boolean owns(TrackVector position) {
        return Math.floor(position.x() / 16) == chunkX && Math.floor(position.z() / 16) == chunkZ;
    }

    private static TrackVector[] section(TrackPose pose, double offset, double halfWidth,
                                          double bottom, double top, double forward) {
        return new TrackVector[]{point(pose, offset - halfWidth, bottom, forward),
                point(pose, offset + halfWidth, bottom, forward),
                point(pose, offset + halfWidth, top, forward),
                point(pose, offset - halfWidth, top, forward)};
    }

    private static TrackVector point(TrackPose pose, double lateral, double normal, double tangent) {
        return new TrackVector(pose.position().x() + pose.lateral().x() * lateral
                + pose.normal().x() * normal + pose.tangent().x() * tangent,
                pose.position().y() + pose.lateral().y() * lateral
                + pose.normal().y() * normal + pose.tangent().y() * tangent,
                pose.position().z() + pose.lateral().z() * lateral
                + pose.normal().z() * normal + pose.tangent().z() * tangent);
    }

    private static final class Builder {
        private final double originX, originZ;
        private double originY;
        private float[] positions = new float[288];
        private int[] colors = new int[96];
        private int count;
        private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        private double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

        private Builder(double originX, double originZ) {
            this.originX = originX;
            this.originZ = originZ;
        }

        private void box(TrackVector[] a, TrackVector[] b, int color, boolean capStart, boolean capEnd) {
            // Sections are CCW toward the forward tangent; all faces below have outward winding.
            quad(a[0], a[1], b[1], b[0], shade(color, 0.55));
            quad(a[1], a[2], b[2], b[1], shade(color, 0.7));
            quad(a[2], a[3], b[3], b[2], color);
            quad(a[3], a[0], b[0], b[3], shade(color, 0.7));
            if (capStart) quad(a[0], a[3], a[2], a[1], shade(color, 0.8));
            if (capEnd) quad(b[0], b[1], b[2], b[3], shade(color, 0.8));
        }

        private static int shade(int color, double brightness) {
            return 0xff000000 | (int)((color >> 16 & 255) * brightness) << 16
                    | (int)((color >> 8 & 255) * brightness) << 8 | (int)((color & 255) * brightness);
        }

        private void quad(TrackVector a, TrackVector b, TrackVector c, TrackVector d, int color) {
            if (count + 4 > MAX_VERTICES) throw new IllegalArgumentException("Track chunk mesh exceeds 262144 vertices; split the track");
            if (count + 4 > colors.length) {
                int capacity = Math.min(MAX_VERTICES, colors.length * 2);
                colors = Arrays.copyOf(colors, capacity);
                positions = Arrays.copyOf(positions, capacity * 3);
            }
            vertex(a, color); vertex(b, color); vertex(c, color); vertex(d, color);
        }

        private void vertex(TrackVector point, int color) {
            if (count == 0) originY = Math.floor(point.y() / 16) * 16;
            float x = (float)(point.x() - originX), y = (float)(point.y() - originY), z = (float)(point.z() - originZ);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("Track mesh coordinates exceed float range");
            }
            positions[count * 3] = x;
            positions[count * 3 + 1] = y;
            positions[count * 3 + 2] = z;
            colors[count++] = color;
            minX = Math.min(minX, point.x()); minY = Math.min(minY, point.y()); minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x()); maxY = Math.max(maxY, point.y()); maxZ = Math.max(maxZ, point.z());
        }

        private TrackMeshData finish() {
            if (count == 0) return TrackMeshData.EMPTY;
            var origin = new TrackVector(originX, originY, originZ);
            var bounds = new TrackMeshData.Bounds(minX, minY, minZ, maxX, maxY, maxZ);
            return new TrackMeshData(origin, Arrays.copyOf(positions, count * 3), Arrays.copyOf(colors, count), bounds);
        }
    }
}
