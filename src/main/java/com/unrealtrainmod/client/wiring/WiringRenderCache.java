/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.GpuResidency;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import com.unrealtrainmod.client.compat.iris.IrisGeometryCache;
import com.unrealtrainmod.client.compat.iris.IrisRenderTypes;
import com.unrealtrainmod.client.renderer.ModelRenderQueue;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Shared model-local GPU storage. No camera, light or Iris identity is baked into geometry. */
public final class WiringRenderCache {
    private record Key(IndexedMesh mesh, WiringGeometry.Material material, Matrix3f linear) {}
    private record Transform(Matrix3f bake, Matrix4f pose, Matrix3f normal) {}
    private static final class Geometry implements AutoCloseable {
        final RenderBatch batch;
        final List<RenderBatch> batches;
        GpuModel gpu;
        long used;
        boolean dynamic = true;
        Geometry(RenderBatch batch) { this.batch = batch; batches = List.of(batch); MESH_OWNERS.merge(batch.mesh(), 1, Integer::sum); }
        GpuModel nativeModel() {
            if (gpu == null) {
                gpu = GpuModel.upload(batches);
                GpuResidency.uploaded(this, GpuResidency.Kind.NATIVE_GEOMETRY, gpu.byteSize(), this::releaseNative);
                WiringRenderStats.uploads++;
            }
            GpuResidency.used(this); return gpu;
        }
        void releaseNative() { if (gpu != null) { gpu.close(); gpu = null; } }
        public void close() {
            GpuResidency.released(this); releaseNative();
            int owners = MESH_OWNERS.get(batch.mesh()) - 1;
            if (owners == 0) { MESH_OWNERS.remove(batch.mesh()); IrisGeometryCache.releaseMesh(batch.mesh()); }
            else MESH_OWNERS.put(batch.mesh(), owners);
        }
    }
    private static final Map<Key, Geometry> GEOMETRY = new HashMap<>();
    private static final Map<IndexedMesh, Integer> MESH_OWNERS = new java.util.IdentityHashMap<>();
    private static final ModelRenderQueue WORLD = new ModelRenderQueue("wiring_world");
    private static long trimmed;
    private WiringRenderCache() {}

    /** offscreen forces the existing native GUI shader scope even while world Iris is active. */
    public static void render(List<WiringGeometry.Draw> draws, PoseStack pose, MultiBufferSource buffers,
                              int light, boolean offscreen, boolean preview) {
        boolean iris = IrisCompat.active() && !offscreen;
        if (iris) IrisCompat.flushEntityBuffers(buffers);
        var queue = iris ? null : offscreen ? new ModelRenderQueue("wiring_gui", true) : WORLD;
        try (var ownedQueue = offscreen ? queue : null;
             var scope = iris ? (preview ? IrisDrawScope.preview() : IrisDrawScope.entity()) : null) {
            for (var draw : draws) {
                var transform = transform(draw);
                if (transform == null) { WiringRenderStats.fallback("degenerate_transform"); continue; }
                var key = new Key(draw.batch().mesh(), draw.batch().material(), transform.bake == null ? new Matrix3f() : transform.bake);
                var geometry = GEOMETRY.get(key);
                if (geometry == null) {
                    geometry = new Geometry(batch(draw.batch(), transform.bake)); GEOMETRY.put(key, geometry);
                    WiringRenderStats.builds++;
                } else WiringRenderStats.hits++;
                geometry.used = IrisGeometryCache.frame();
                geometry.dynamic &= draw.batch().dynamic();
                pose.pushPose();
                try {
                    pose.last().pose().mul(transform.pose); pose.last().normal().mul(transform.normal);
                    int packed = draw.batch().material().light() ? 0xF000F0 : draw.light() >= 0 ? draw.light() : light;
                    if (!iris) {
                        if (com.unrealtrainmod.client.StaticModelShader.state() == null) WiringRenderStats.fallback("native_shader_unavailable");
                        queue.submitBatches(geometry.nativeModel(), geometry.batches, pose.last(), packed, true);
                    }
                    else {
                        if (IrisCompat.shadowPass() && geometry.batch.category() == RenderCategory.BLEND) continue;
                        if (!IrisGeometryCache.supportsPose(pose.last().pose())) {
                            WiringRenderStats.fallback("unsupported_pose"); cpu(geometry.batch, pose, buffers, packed); continue;
                        }
                        var batch = geometry.batch;
                        var mesh = IrisGeometryCache.requestStatic(batch.mesh(), batch.firstIndex(), batch.indexCount());
                        if (mesh == null) { WiringRenderStats.fallback("cache_unavailable"); cpu(batch, pose, buffers, packed); continue; }
                        scope.drawCached(mesh, IrisRenderTypes.get(batch.texture(), batch.doubleSided(),
                                        batch.category() == RenderCategory.BLEND, false),
                                new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose()),
                                RenderSystem.getProjectionMatrix(), packed);
                    }
                    WiringRenderStats.draws++;
                } finally { pose.popPose(); }
            }
            if (offscreen) { queue.drawOpaque(); queue.drawTranslucent(); }
        }
    }
    private static Transform transform(WiringGeometry.Draw draw) {
        if (IrisGeometryCache.supportsPose(draw.pose())) return new Transform(null, draw.pose(), draw.normal());
        var linear = new Matrix3f(draw.pose());
        if (!Float.isFinite(linear.determinant()) || Math.abs(linear.determinant()) < 1e-12f) return null;
        var x = linear.getColumn(0, new Vector3f()); var y = linear.getColumn(1, new Vector3f()); var z = linear.getColumn(2, new Vector3f());
        float sx = x.length(), sy = y.length(), sz = z.length(); x.div(sx); y.div(sy); z.div(sz);
        if (Math.abs(x.dot(y)) < 1e-5f && Math.abs(x.dot(z)) < 1e-5f && Math.abs(y.dot(z)) < 1e-5f) {
            // Keep yaw/pitch and translation as instance parameters; only the authored scale changes geometry.
            if (linear.determinant() < 0) { sx = -sx; x.negate(); }
            var rotation = new Matrix3f().setColumn(0, x).setColumn(1, y).setColumn(2, z);
            return new Transform(new Matrix3f().scaling(sx, sy, sz), new Matrix4f(draw.pose()).set3x3(rotation), rotation);
        }
        // Actual shear cannot be represented by the cached Iris pose; bake its complete linear transform once.
        return new Transform(linear, new Matrix4f().translation(draw.pose().m30(), draw.pose().m31(), draw.pose().m32()), new Matrix3f());
    }
    private static RenderBatch batch(WiringGeometry.Batch source, Matrix3f linear) {
        var mesh = source.mesh();
        if (linear != null) {
            var vertices = mesh.vertices().clone(); var normalMatrix = new Matrix3f(linear).invert().transpose();
            var value = new Vector3f();
            for (int i = 0; i < vertices.length; i += 8) {
                value.set(vertices[i], vertices[i + 1], vertices[i + 2]).mul(linear);
                vertices[i] = value.x; vertices[i + 1] = value.y; vertices[i + 2] = value.z;
                value.set(vertices[i + 5], vertices[i + 6], vertices[i + 7]).mul(normalMatrix).normalize();
                vertices[i + 5] = value.x; vertices[i + 6] = value.y; vertices[i + 7] = value.z;
            }
            int[] indices = mesh.indices();
            if (linear.determinant() < 0) {
                indices = indices.clone();
                for (int i = 0; i < indices.length; i += 3) { int swap = indices[i + 1]; indices[i + 1] = indices[i + 2]; indices[i + 2] = swap; }
            }
            mesh = new IndexedMesh(vertices, mesh.colors(), indices);
        }
        var material = source.material();
        return new RenderBatch(mesh, material.texture(), material.translucent() ? RenderCategory.BLEND : RenderCategory.CUTOUT,
                material.translucent() ? MaterialRenderMode.TRANSLUCENT : MaterialRenderMode.CUTOUT, material.doubleSided());
    }
    private static void cpu(RenderBatch batch, PoseStack pose, MultiBufferSource buffers, int light) {
        var target = buffers.getBuffer(com.unrealtrainmod.client.renderer.ModelRenderTypes.get(batch.texture(),
                batch.doubleSided(), batch.category() == RenderCategory.BLEND, false));
        var vertices = new com.unrealtrainmod.client.compat.iris.IrisVertices();
        for (int index : batch.mesh().indices()) vertices.vertex(target, batch.mesh(), index, pose.last(), light, false);
    }
    public static void endFrame() {
        long frame = IrisGeometryCache.frame();
        GEOMETRY.values().removeIf(g -> { if (!g.dynamic || g.used >= frame - 1) return false; g.close(); return true; });
        if (frame - trimmed >= 120) {
            trimmed = frame;
            GEOMETRY.values().removeIf(g -> { if (g.used >= frame - 120) return false; g.close(); return true; });
            WiringClient.trimShapes(frame);
        }
        WiringRenderStats.log();
    }
    public static void beginFrame() { WORLD.clear(); }
    public static void flushWorld() {
        try { WORLD.drawOpaque(); WORLD.drawTranslucent(); }
        finally { WORLD.clear(); }
    }
    public static void clear() { WORLD.close(); GEOMETRY.values().forEach(Geometry::close); GEOMETRY.clear(); }
}
