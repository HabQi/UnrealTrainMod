/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Rail-only static repetition with endpoint tangent extension. No textures, Minecraft classes, or GPU resources. */
public final class RailTrackMeshBuilder {
    private static final int MAX_VERTICES = 262_144;
    private final TrackGeometry geometry;
    private final int chunkX, chunkZ;
    private final double length;
    private final double scale, period;
    private final long count;
    private final TrackVector origin;
    private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    private double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
    private int vertexCount;

    private RailTrackMeshBuilder(TrackGeometry geometry, int chunkX, int chunkZ, double scale, double referencePeriod) {
        this.geometry = geometry;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.scale = scale;
        period = referencePeriod * scale;
        if (!Double.isFinite(scale) || scale <= 0 || !Double.isFinite(period) || period <= 0) {
            throw new IllegalArgumentException("Rail model scale and period must be finite and positive");
        }
        length = geometry.getLength();
        if (length / period >= Long.MAX_VALUE) throw new IllegalArgumentException("Rail model repetition count is too large");
        count = (long)Math.ceil(length / period);
        origin = new TrackVector((double)chunkX * 16, Math.floor(geometry.sample(0).position().y() / 16) * 16, (double)chunkZ * 16);
    }

    public static RailTrackMeshData build(TrackGeometry geometry, List<IndexedMesh> sources, int chunkX, int chunkZ,
                                          double scale, double referencePeriod) {
        var builder = new RailTrackMeshBuilder(geometry, chunkX, chunkZ, scale, referencePeriod);
        var batches = new ArrayList<RailTrackMeshData.Batch>();
        for (int i = 0; i < sources.size(); i++) {
            var vertices = new FloatArrayList();
            var colors = new IntArrayList();
            builder.cells(sources.get(i), 0, builder.count, vertices, colors);
            if (!colors.isEmpty()) batches.add(new RailTrackMeshData.Batch(i,
                    new IndexedMesh(vertices.toFloatArray(), colors.toIntArray(), IntStream.range(0, colors.size()).toArray())));
        }
        var bounds = batches.isEmpty() ? new TrackMeshData.Bounds(0, 0, 0, 0, 0, 0)
                : new TrackMeshData.Bounds(builder.minX, builder.minY, builder.minZ, builder.maxX, builder.maxY, builder.maxZ);
        return new RailTrackMeshData(builder.origin, bounds, batches);
    }

    private void cells(IndexedMesh source, long first, long end, FloatArrayList vertices, IntArrayList colors) {
        double a = first * period, b = Math.min(length, end * period);
        TrackVector middle = geometry.sample(a + (b - a) / 2).position();
        double radius = (b - a) / 2 + 0.001, x = (double)chunkX * 16, z = (double)chunkZ * 16;
        if (middle.x() + radius < x || middle.x() - radius >= x + 16
                || middle.z() + radius < z || middle.z() - radius >= z + 16) return;
        if (end - first > 1) {
            long split = first + (end - first) / 2;
            cells(source, first, split, vertices, colors);
            cells(source, split, end, vertices, colors);
            return;
        }
        // Own an entire repeated module by its within-track interval midpoint, never by individual faces.
        if (Math.floor(middle.x() / 16) != chunkX || Math.floor(middle.z() / 16) != chunkZ) return;
        double center = first * period + period / 2;
        int[] indices = source.indices();
        for (int index : indices) emit(source, index, center, vertices, colors);
    }

    private void emit(IndexedMesh mesh, int index, double center, FloatArrayList vertices, IntArrayList colors) {
        if (++vertexCount > MAX_VERTICES) throw new IllegalArgumentException("Rail chunk mesh exceeds 262,144 vertices");
        float[] v = mesh.vertices();
        int offset = index * 8;
        double distance = v[offset + 2] * scale + center;
        double sampledDistance = Math.clamp(distance, 0, length);
        var pose = geometry.sample(sampledDistance);
        var p = pose.position(); var l = pose.lateral(); var n = pose.normal(); var t = pose.tangent();
        // Preserve overhanging geometry by extending the centerline along its endpoint tangent.
        double extension = distance - sampledDistance;
        double x = v[offset] * scale, y = v[offset + 1] * scale;
        double xw = p.x() + l.x() * x + n.x() * y + t.x() * extension;
        double yw = p.y() + l.y() * x + n.y() * y + t.y() * extension;
        double zw = p.z() + l.z() * x + n.z() * y + t.z() * extension;
        double nx = l.x() * v[offset + 5] + n.x() * v[offset + 6] + t.x() * v[offset + 7];
        double ny = l.y() * v[offset + 5] + n.y() * v[offset + 6] + t.y() * v[offset + 7];
        double nz = l.z() * v[offset + 5] + n.z() * v[offset + 6] + t.z() * v[offset + 7];
        double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (normalLength > 0) { nx /= normalLength; ny /= normalLength; nz /= normalLength; }
        for (double value : new double[]{xw - origin.x(), yw - origin.y(), zw - origin.z(), v[offset + 3], v[offset + 4], nx, ny, nz}) {
            float component = (float)value;
            if (!Float.isFinite(component)) throw new IllegalArgumentException("Non-finite rail vertex");
            vertices.add(component);
        }
        colors.add(mesh.colors()[index]);
        minX = Math.min(minX, xw); maxX = Math.max(maxX, xw);
        minY = Math.min(minY, yw); maxY = Math.max(maxY, yw);
        minZ = Math.min(minZ, zw); maxZ = Math.max(maxZ, zw);
    }
}
