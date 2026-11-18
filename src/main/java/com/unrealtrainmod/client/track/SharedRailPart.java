/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.ModelPreparation;
import com.unrealtrainmod.client.ModelUploads;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** One original rigid VBO/IBO per mesh and resource generation, shared by every turnout. */
final class SharedRailPart implements AutoCloseable {
    final IndexedMesh source;
    private AABB bounds;
    GpuModel.Mesh gpu;
    private GpuModel storage;
    private ModelPreparation.Ticket<Prepared> pending;
    private GpuModel.Upload upload;
    private int generation;
    private boolean failed;
    private ModelPreparation.Ticket<AABB> pendingBounds;
    private final RenderBatch batch;
    private record Prepared(GpuModel.Prepared geometry, AABB bounds) implements AutoCloseable {
        long bytes() { return geometry.byteSize(); }
        @Override public void close() { geometry.close(); }
    }
    SharedRailPart(IndexedMesh source) {
        this.source = source;
        batch = new RenderBatch(source, com.unrealtrainmod.client.ClientModelManager.WHITE,
                com.unrealtrainmod.client.RenderCategory.CUTOUT, null, false);
    }
    private AABB bounds() {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        var v = source.vertices();
        for (int index : source.indices()) {
            int i = index * 8;
            minX = Math.min(minX, v[i]); minY = Math.min(minY, v[i + 1]); minZ = Math.min(minZ, v[i + 2]);
            maxX = Math.max(maxX, v[i]); maxY = Math.max(maxY, v[i + 1]); maxZ = Math.max(maxZ, v[i + 2]);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
    private Prepared prepare() {
        var bounds = bounds();
        return new Prepared(GpuModel.prepare(java.util.List.of(batch)), bounds);
    }
    boolean boundsReady() {
        if (bounds != null || failed) return true;
        if (pendingBounds == null) {
            var ticket = ModelPreparation.geometry(this::bounds, ignored -> 48L, ignored -> {});
            if (ticket == null) return false;
            pendingBounds = ticket;
            ticket.whenReady(() -> {
                try { bounds = ticket.take(); }
                catch (RuntimeException exception) {
                    failed = true; ticket.close();
                    org.slf4j.LoggerFactory.getLogger(SharedRailPart.class).warn("Cannot prepare rail part bounds", exception);
                }
                finally { pendingBounds = null; }
            });
        }
        return false;
    }
    void upload(TrackRenderStats stats) {
        if (gpu != null || failed || pending != null) return;
        pending = ModelPreparation.geometry(this::prepare, Prepared::bytes, Prepared::close);
        if (pending == null) return;
        int version = generation;
        ModelUploads.enqueue(() -> {
            if (version != generation) return true;
            if (!pending.ready()) return false;
            try {
                var prepared = pending.value(); bounds = prepared.bounds();
                if (upload == null) upload = new GpuModel.Upload(prepared.geometry());
                if (!upload.step()) return false;
                ModelUploads.commit(() -> {
                    if (version != generation) return;
                    storage = upload.finish(); gpu = storage.part(batch).mesh(); upload.close(); upload = null;
                    long size = pending.value().bytes(); pending.close(); pending = null;
                    if (stats != null) stats.uploaded((int)size, 0, (int)size);
                });
            } catch (RuntimeException exception) {
                failed = true;
                if (upload != null) { upload.close(); upload = null; }
                pending.close(); pending = null;
                org.slf4j.LoggerFactory.getLogger(SharedRailPart.class).warn("Cannot prepare shared rail part; using supported fallback", exception);
            }
            return true;
        });
    }
    boolean ready(TrackRenderStats stats) { upload(stats); return gpu != null || failed; }
    AABB transformedBounds(Matrix4f transform, double x, double y, double z) {
        if (bounds == null) return new AABB(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
        double cx = (bounds.minX + bounds.maxX) / 2, cy = (bounds.minY + bounds.maxY) / 2, cz = (bounds.minZ + bounds.maxZ) / 2;
        double ex = (bounds.maxX - bounds.minX) / 2, ey = (bounds.maxY - bounds.minY) / 2, ez = (bounds.maxZ - bounds.minZ) / 2;
        double px = x + transform.m00() * cx + transform.m10() * cy + transform.m20() * cz + transform.m30();
        double py = y + transform.m01() * cx + transform.m11() * cy + transform.m21() * cz + transform.m31();
        double pz = z + transform.m02() * cx + transform.m12() * cy + transform.m22() * cz + transform.m32();
        double dx = Math.abs(transform.m00()) * ex + Math.abs(transform.m10()) * ey + Math.abs(transform.m20()) * ez;
        double dy = Math.abs(transform.m01()) * ex + Math.abs(transform.m11()) * ey + Math.abs(transform.m21()) * ez;
        double dz = Math.abs(transform.m02()) * ex + Math.abs(transform.m12()) * ey + Math.abs(transform.m22()) * ez;
        return new AABB(px - dx, py - dy, pz - dz, px + dx, py + dy, pz + dz);
    }
    @Override public void close() {
        generation++;
        if (pendingBounds != null) { pendingBounds.close(); pendingBounds = null; }
        if (pending != null) { pending.close(); pending = null; }
        if (upload != null) { upload.close(); upload = null; }
        if (storage != null) { storage.close(); storage = null; gpu = null; }
    }
}
