/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.StaticModelShader;
import com.unrealtrainmod.model.IndexedMesh;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** One original rigid VBO/IBO per model mesh and resource generation, shared by every turnout. */
final class SharedRailPart implements AutoCloseable {
    final IndexedMesh source;
    private final AABB bounds;
    GpuModel.Mesh gpu;

    SharedRailPart(IndexedMesh source) {
        this.source = source;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        var v = source.vertices();
        for (int index : source.indices()) {
            int i = index * 8;
            minX = Math.min(minX, v[i]); minY = Math.min(minY, v[i + 1]); minZ = Math.min(minZ, v[i + 2]);
            maxX = Math.max(maxX, v[i]); maxY = Math.max(maxY, v[i + 1]); maxZ = Math.max(maxZ, v[i + 2]);
        }
        bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    void upload(TrackRenderStats stats) {
        long start = stats == null ? 0 : System.nanoTime();
        gpu = GpuModel.uploadSharedMesh(source);
        if (stats != null) {
            stats.gpuUpload(System.nanoTime() - start);
            stats.uploaded(source.colors().length * StaticModelShader.FORMAT.getVertexSize(),
                    source.indices().length * Integer.BYTES, 0);
        }
    }

    AABB transformedBounds(Matrix4f transform, double x, double y, double z) {
        double cx = (bounds.minX + bounds.maxX) / 2, cy = (bounds.minY + bounds.maxY) / 2, cz = (bounds.minZ + bounds.maxZ) / 2;
        double ex = (bounds.maxX - bounds.minX) / 2, ey = (bounds.maxY - bounds.minY) / 2, ez = (bounds.maxZ - bounds.minZ) / 2;
        double px = x + transform.m00() * cx + transform.m10() * cy + transform.m20() * cz + transform.m30();
        double py = y + transform.m01() * cx + transform.m11() * cy + transform.m21() * cz + transform.m31();
        double pz = z + transform.m02() * cx + transform.m12() * cy + transform.m22() * cz + transform.m32();
        double dx = Math.abs(transform.m00()) * ex + Math.abs(transform.m10()) * ey + Math.abs(transform.m20()) * ez;
        double dy = Math.abs(transform.m01()) * ex + Math.abs(transform.m11()) * ey + Math.abs(transform.m21()) * ez;
        double dz = Math.abs(transform.m02()) * ex + Math.abs(transform.m12()) * ey + Math.abs(transform.m22()) * ez;
        return new AABB(px - dx, py - dy, pz - dz, px + dx, py + dy, pz + dz).inflate(0.001);
    }

    @Override public void close() {
        if (gpu != null) { gpu.close(); gpu = null; }
    }
}
