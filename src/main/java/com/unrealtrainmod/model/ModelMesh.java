/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prepared once on resource reload. Source coordinates are scaled to blocks without changing the origin. */
public record ModelMesh(List<Part> parts, int triangleCount) {
    public record Part(String name, List<Batch> batches) {}
    public record Batch(ModelData.Material material, IndexedMesh mesh) {}

    public static ModelMesh prepare(ModelData model, float scale, boolean smoothing) throws IOException {
        if (!Float.isFinite(scale) || scale <= 0) throw new IOException("Model scale must be positive");
        List<Part> parts = new ArrayList<>();
        int triangles = 0;
        for (var part : model.parts()) {
            if (!part.visible()) continue;
            boolean smooth = smoothing && part.smooth();
            int faceCount = part.faces().size();
            float[][] normals = new float[faceCount][];
            for (int f = 0; f < faceCount; f++) {
                normals[f] = normal(part.faces().get(f), part.positions());
            }
            int[] offsets = null, neighbors = null;
            if (smooth) {
                offsets = new int[part.positions().size() + 1];
                for (var face : part.faces()) for (int v : face.indices()) offsets[v + 1]++;
                for (int i = 1; i < offsets.length; i++) offsets[i] += offsets[i - 1];
                neighbors = new int[offsets[offsets.length - 1]];
                int[] cursor = offsets.clone();
                for (int f = 0; f < faceCount; f++) for (int v : part.faces().get(f).indices()) {
                    neighbors[cursor[v]++] = f;
                }
            }
            float threshold = (float)Math.cos(Math.toRadians(Math.clamp(part.facet(), 0, 180)));
            Map<Integer, BatchBuilder> batches = new LinkedHashMap<>();
            int[] vertexIndices = new int[3];
            for (int f = 0; f < faceCount; f++) {
                var face = part.faces().get(f);
                if (face.indices().length < 3 || isZero(normals[f])) continue;
                int[] corners = triangulate(face, part.positions(), normals[f]);
                var material = face.material() < 0 ? ModelData.Material.DEFAULT : model.materials().get(face.material());
                BatchBuilder batch = batches.computeIfAbsent(face.material(), ignored -> new BatchBuilder(material));
                if (vertexIndices.length < face.indices().length) vertexIndices = new int[face.indices().length];
                // Compute each original face corner once, even when triangulation reuses it.
                for (int corner = 0; corner < face.indices().length; corner++) {
                    int index = face.indices()[corner];
                    var p = part.positions().get(index);
                    float nx = normals[f][0], ny = normals[f][1], nz = normals[f][2];
                    if (smooth) {
                        nx = 0; ny = 0; nz = 0;
                        for (int j = offsets[index]; j < offsets[index + 1]; j++) {
                            float[] n = normals[neighbors[j]];
                            if (dot(normals[f], n) >= threshold) { nx += n[0]; ny += n[1]; nz += n[2]; }
                        }
                        float magnitude = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (magnitude > 0) { nx /= magnitude; ny /= magnitude; nz /= magnitude; }
                        else { nx = normals[f][0]; ny = normals[f][1]; nz = normals[f][2]; }
                    }
                    vertexIndices[corner] = batch.mesh.vertex(p.x() * scale, p.y() * scale, p.z() * scale,
                            face.uv()[corner * 2], face.uv()[corner * 2 + 1], nx, ny, nz,
                            tint(face.colors()[corner], material));
                }
                for (int t = 0; t < corners.length; t += 3) {
                    // MQO front faces are clockwise; emit the opposite winding for Minecraft.
                    batch.mesh.triangle(vertexIndices[corners[t + 2]], vertexIndices[corners[t + 1]], vertexIndices[corners[t]]);
                }
                triangles += corners.length / 3;
            }
            parts.add(new Part(part.name(), batches.values().stream().map(BatchBuilder::build).toList()));
        }
        if (triangles == 0) throw new IOException("Model contains no renderable faces");
        return new ModelMesh(List.copyOf(parts), triangles);
    }

    private static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static boolean isZero(float[] n) { return n[0] == 0 && n[1] == 0 && n[2] == 0; }

    private static float[] normal(ModelData.Face face, List<ModelData.Position> positions) {
        double x = 0, y = 0, z = 0;
        int[] v = face.indices();
        for (int i = 0; i < v.length; i++) {
            var a = positions.get(v[i]); var b = positions.get(v[(i + 1) % v.length]);
            x += ((double)a.y() - b.y()) * (a.z() + b.z());
            y += ((double)a.z() - b.z()) * (a.x() + b.x());
            z += ((double)a.x() - b.x()) * (a.y() + b.y());
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1e-12 ? new float[3] : new float[]{(float)(-x / length), (float)(-y / length), (float)(-z / length)};
    }

    /** Ear clipping on the dominant plane handles concave polygons as well as triangles and quads. */
    private static int[] triangulate(ModelData.Face face, List<ModelData.Position> positions, float[] normal) throws IOException {
        int count = face.indices().length;
        if (count == 3) return new int[]{0, 1, 2};
        int axis = Math.abs(normal[0]) > Math.abs(normal[1]) ? 0 : 1;
        if (Math.abs(normal[2]) > Math.abs(normal[axis])) axis = 2;
        double[] x = new double[count], y = new double[count];
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var p = positions.get(face.indices()[i]);
            x[i] = axis == 0 ? p.y() : p.x(); y[i] = axis == 2 ? p.y() : p.z();
            remaining.add(i);
        }
        double area = 0;
        for (int i = 1; i < count - 1; i++) area += cross(x, y, 0, i, i + 1);
        double sign = Math.signum(area);
        int[] result = new int[(count - 2) * 3];
        int output = 0;
        while (remaining.size() > 3) {
            boolean clipped = false;
            for (int i = 0; i < remaining.size(); i++) {
                int a = remaining.get((i + remaining.size() - 1) % remaining.size());
                int b = remaining.get(i), c = remaining.get((i + 1) % remaining.size());
                if (cross(x, y, a, b, c) * sign <= 1e-12) continue;
                boolean contains = false;
                for (int p : remaining) if (p != a && p != b && p != c
                        && cross(x, y, a, b, p) * sign >= -1e-12
                        && cross(x, y, b, c, p) * sign >= -1e-12
                        && cross(x, y, c, a, p) * sign >= -1e-12) { contains = true; break; }
                if (contains) continue;
                result[output++] = a; result[output++] = b; result[output++] = c;
                remaining.remove(i); clipped = true; break;
            }
            if (!clipped) throw new IOException("Cannot triangulate degenerate or self-intersecting MQO polygon");
        }
        for (int corner : remaining) result[output++] = corner;
        return result;
    }

    private static double cross(double[] x, double[] y, int a, int b, int c) {
        return (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a]);
    }

    private static int tint(int argb, ModelData.Material m) {
        return channel(argb >>> 24, m.alpha()) << 24 | channel(argb >>> 16 & 255, m.red()) << 16
                | channel(argb >>> 8 & 255, m.green()) << 8 | channel(argb & 255, m.blue());
    }

    private static int channel(int value, float multiplier) { return Math.clamp(Math.round(value * multiplier), 0, 255); }

    private static final class BatchBuilder {
        private final ModelData.Material material;
        private final IndexedMesh.Builder mesh = new IndexedMesh.Builder();
        private BatchBuilder(ModelData.Material material) { this.material = material; }
        private Batch build() { return new Batch(material, mesh.build()); }
    }
}
