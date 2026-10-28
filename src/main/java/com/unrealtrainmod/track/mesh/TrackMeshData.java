/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackVector;

/** Owned immutable quad data. No GPU, Minecraft or vehicle-model objects are retained. */
public final class TrackMeshData {
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public static final TrackMeshData EMPTY = new TrackMeshData(new TrackVector(0, 0, 0),
            new float[0], new int[0], new Bounds(0, 0, 0, 0, 0, 0));

    private final TrackVector origin;
    private final float[] positions;
    private final int[] colors;
    private final Bounds bounds;

    TrackMeshData(TrackVector origin, float[] positions, int[] colors, Bounds bounds) {
        this.origin = origin;
        this.positions = positions;
        this.colors = colors;
        this.bounds = bounds;
    }

    public TrackVector origin() { return origin; }
    public Bounds bounds() { return bounds; }
    public int vertexCount() { return colors.length; }
    public float x(int index) { return positions[index * 3]; }
    public float y(int index) { return positions[index * 3 + 1]; }
    public float z(int index) { return positions[index * 3 + 2]; }
    public int color(int index) { return colors[index]; }
}
