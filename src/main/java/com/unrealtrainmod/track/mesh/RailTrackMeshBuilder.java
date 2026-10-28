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

/** Rail-only static repetition and endpoint clipping. No textures, Minecraft classes, or GPU resources. */
public final class RailTrackMeshBuilder {
    public static final double MODEL_SCALE = 0.01;
    public static final double TOP_OFFSET = 0.25;
    public static final double PERIOD = 0.625;
    private static final int MAX_VERTICES = 262_144;
    private final TrackGeometry geometry;
    private final int chunkX, chunkZ;
    private final double length;
    private final long count;
    private final TrackVector origin;
    private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    private double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
    private int vertexCount;

    private RailTrackMeshBuilder(TrackGeometry geometry, int chunkX, int chunkZ) {
        this.geometry = geometry;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        length = geometry.getLength();
        if (length / PERIOD >= Long.MAX_VALUE) throw new IllegalArgumentException("Rail model repetition count is too large");
        count = (long)Math.ceil(length / PERIOD);
        origin = new TrackVector((double)chunkX * 16, Math.floor(geometry.sample(0).position().y() / 16) * 16, (double)chunkZ * 16);
    }

    public static RailTrackMeshData build(TrackGeometry geometry, List<IndexedMesh> sources, int chunkX, int chunkZ) {
        var builder = new RailTrackMeshBuilder(geometry, chunkX, chunkZ);
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
        double a = first * PERIOD, b = Math.min(length, end * PERIOD);
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
        // Own an entire repeated module by its clipped along-track interval midpoint, never by individual faces.
        if (Math.floor(middle.x() / 16) != chunkX || Math.floor(middle.z() / 16) != chunkZ) return;
        double center = first * PERIOD + PERIOD / 2;
        int[] indices = source.indices();
        for (int i = 0; i < indices.length; i += 3) {
            List<Vertex> polygon = List.of(vertex(source, indices[i], center), vertex(source, indices[i + 1], center),
                    vertex(source, indices[i + 2], center));
            polygon = clip(clip(polygon, 0, true), length, false);
            for (int j = 1; j + 1 < polygon.size(); j++) {
                emit(polygon.get(0), vertices, colors);
                emit(polygon.get(j), vertices, colors);
                emit(polygon.get(j + 1), vertices, colors);
            }
        }
    }

    private static Vertex vertex(IndexedMesh mesh, int index, double center) {
        float[] data = mesh.vertices();
        int offset = index * 8, color = mesh.colors()[index];
        return new Vertex(new double[]{data[offset], data[offset + 1], data[offset + 2] + center,
                data[offset + 3], data[offset + 4], data[offset + 5], data[offset + 6], data[offset + 7],
                (color >>> 24) & 255, (color >>> 16) & 255, (color >>> 8) & 255, color & 255});
    }

    private static List<Vertex> clip(List<Vertex> polygon, double boundary, boolean greater) {
        if (polygon.isEmpty()) return polygon;
        var result = new ArrayList<Vertex>(polygon.size() + 1);
        Vertex previous = polygon.getLast();
        boolean previousInside = inside(previous, boundary, greater);
        for (Vertex current : polygon) {
            boolean currentInside = inside(current, boundary, greater);
            if (currentInside != previousInside) {
                double t = (boundary - previous.data[2]) / (current.data[2] - previous.data[2]);
                double[] data = new double[12];
                for (int i = 0; i < data.length; i++) data[i] = previous.data[i] + (current.data[i] - previous.data[i]) * t;
                data[2] = boundary;
                result.add(new Vertex(data));
            }
            if (currentInside) result.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return result;
    }

    private static boolean inside(Vertex v, double boundary, boolean greater) {
        return greater ? v.data[2] >= boundary : v.data[2] <= boundary;
    }

    private void emit(Vertex vertex, FloatArrayList vertices, IntArrayList colors) {
        if (++vertexCount > MAX_VERTICES) throw new IllegalArgumentException("RTM rail chunk mesh exceeds 262,144 vertices");
        double[] v = vertex.data;
        var pose = geometry.sample(v[2]);
        var p = pose.position(); var l = pose.lateral(); var n = pose.normal(); var t = pose.tangent();
        double y = v[1] - TOP_OFFSET;
        double xw = p.x() + l.x() * v[0] + n.x() * y;
        double yw = p.y() + l.y() * v[0] + n.y() * y;
        double zw = p.z() + l.z() * v[0] + n.z() * y;
        double nx = l.x() * v[5] + n.x() * v[6] + t.x() * v[7];
        double ny = l.y() * v[5] + n.y() * v[6] + t.y() * v[7];
        double nz = l.z() * v[5] + n.z() * v[6] + t.z() * v[7];
        double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (normalLength > 0) { nx /= normalLength; ny /= normalLength; nz /= normalLength; }
        for (double value : new double[]{xw - origin.x(), yw - origin.y(), zw - origin.z(), v[3], v[4], nx, ny, nz}) {
            float component = (float)value;
            if (!Float.isFinite(component)) throw new IllegalArgumentException("Non-finite RTM rail vertex");
            vertices.add(component);
        }
        int color = 0;
        for (int i = 8; i < 12; i++) color = (color << 8) | (int)Math.clamp(Math.round(v[i]), 0, 255);
        colors.add(color);
        minX = Math.min(minX, xw); maxX = Math.max(maxX, xw);
        minY = Math.min(minY, yw); maxY = Math.max(maxY, yw);
        minZ = Math.min(minZ, zw); maxZ = Math.max(maxZ, zw);
    }

    private record Vertex(double[] data) {}
}
