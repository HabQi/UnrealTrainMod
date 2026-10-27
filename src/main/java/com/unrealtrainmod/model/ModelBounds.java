/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

/** Bounds in model coordinates, in blocks. The authored origin is never moved. */
public record ModelBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    public static final ModelBounds MISSING = new ModelBounds(-0.5f, 0, -0.5f, 0.5f, 1, 0.5f);

    public float width() { return maxX - minX; }
    public float height() { return maxY - minY; }
    public float length() { return maxZ - minZ; }
    public float centerX() { return (minX + maxX) / 2; }
    public float centerY() { return (minY + maxY) / 2; }
    public float centerZ() { return (minZ + maxZ) / 2; }
    public float radius() {
        return (float)Math.sqrt(width() * width() + height() * height() + length() * length()) / 2;
    }

    public ModelBounds translated(ModelData.Position offset) {
        return new ModelBounds(minX + offset.x(), minY + offset.y(), minZ + offset.z(),
                maxX + offset.x(), maxY + offset.y(), maxZ + offset.z());
    }

    public static final class Builder {
        private float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

        public void include(float x, float y, float z) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }

        public void include(ModelBounds bounds) {
            include(bounds.minX, bounds.minY, bounds.minZ);
            include(bounds.maxX, bounds.maxY, bounds.maxZ);
        }

        /** Null denotes a source with no visible vertices. */
        public ModelBounds build() {
            return minX == Float.POSITIVE_INFINITY ? null : new ModelBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
