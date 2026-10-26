/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Indexed triangles. Eight floats per vertex: XYZ, UV, normal XYZ; colors are ARGB. */
public record IndexedMesh(float[] vertices, int[] colors, int[] indices) {
    /** Position, UV, normal and color all participate in vertex sharing within one batch. */
    static final class Builder {
        private float[] vertices = new float[8192];
        private int[] colors = new int[1024];
        private int[] indices = new int[3072];
        private final Map<VertexKey, Integer> lookup = new HashMap<>();
        private int vertexCount, indexCount;

        int vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int color) {
            var key = new VertexKey(bits(x), bits(y), bits(z), bits(u), bits(v),
                    bits(nx), bits(ny), bits(nz), color);
            Integer existing = lookup.get(key);
            if (existing != null) return existing;
            if (vertexCount == colors.length) {
                colors = Arrays.copyOf(colors, vertexCount * 2);
                vertices = Arrays.copyOf(vertices, vertexCount * 16);
            }
            int index = vertexCount++, offset = index * 8;
            vertices[offset] = x; vertices[offset + 1] = y; vertices[offset + 2] = z;
            vertices[offset + 3] = u; vertices[offset + 4] = v;
            vertices[offset + 5] = nx; vertices[offset + 6] = ny; vertices[offset + 7] = nz;
            colors[index] = color;
            lookup.put(key, index);
            return index;
        }

        void triangle(int a, int b, int c) {
            if (indexCount == indices.length) indices = Arrays.copyOf(indices, indexCount * 2);
            indices[indexCount++] = a;
            indices[indexCount++] = b;
            indices[indexCount++] = c;
        }

        IndexedMesh build() {
            return new IndexedMesh(Arrays.copyOf(vertices, vertexCount * 8), Arrays.copyOf(colors, vertexCount),
                    Arrays.copyOf(indices, indexCount));
        }

        private static int bits(float value) { return Float.floatToIntBits(value); }
    }

    private record VertexKey(int x, int y, int z, int u, int v, int nx, int ny, int nz, int color) {}
}
