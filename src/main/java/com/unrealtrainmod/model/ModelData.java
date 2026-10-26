/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.util.List;

/** Source geometry, independent of Minecraft rendering and of the input file format. */
public record ModelData(List<Material> materials, List<Part> parts) {
    public ModelData {
        materials = List.copyOf(materials);
        parts = List.copyOf(parts);
    }

    public record Position(float x, float y, float z) {}

    public record Material(String name, float red, float green, float blue, float alpha,
                           String texture, boolean doubleSided) {
        public static final Material DEFAULT = new Material("default", 1, 1, 1, 1, null, false);
    }

    /** UVs and colors belong to face corners, not shared position vertices. */
    public record Face(int[] indices, float[] uv, int[] colors, int material) {}

    public record Part(String name, boolean visible, boolean smooth, float facet,
                       List<Position> positions, List<Face> faces) {
        public Part {
            positions = List.copyOf(positions);
            faces = List.copyOf(faces);
        }
    }

    public int faceCount() {
        return parts.stream().mapToInt(p -> p.faces().size()).sum();
    }
}
