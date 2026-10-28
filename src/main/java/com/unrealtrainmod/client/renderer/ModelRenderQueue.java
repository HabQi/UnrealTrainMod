/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.ClientModelManager.RenderBatch;
import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.StaticModelShader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One world view or one preview. No per-triangle objects and no vertex uploads on the normal GPU path. */
public final class ModelRenderQueue implements AutoCloseable {
    private record Draw(RenderBatch batch, GpuModel.Part gpu, PoseStack.Pose pose, int light) {}
    private final List<Draw> draws = new ArrayList<>();
    private final IdentityHashMap<GpuModel.Mesh, Boolean> sortedMeshes = new IdentityHashMap<>();
    private final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f();
    private final Vector3f normal = new Vector3f();
    private int[] triangleDraw = new int[0], triangleIndex = new int[0], order = new int[0];
    private int[] sortScratch = new int[0], gpuOffset = new int[0];
    private float[] depth = new float[0];
    private int triangles;
    private boolean started, customShader;
    private ByteBufferBuilder fallbackBuffer;

    public void submit(ClientModelManager.LoadedModel model, PoseStack pose, int light) {
        for (var instance : model.instances()) submit(instance, instance.model().visibleParts(), pose, light);
    }

    /** Supports the existing exact-name selection API, including explicitly selected hidden parts. */
    public void submit(ClientModelManager.LoadedInstance instance, ClientModelManager.PartSelection selection,
                       PoseStack pose, int light) {
        RenderSystem.assertOnRenderThread();
        if (!started) {
            modelView.set(RenderSystem.getModelViewMatrix());
            projection.set(RenderSystem.getProjectionMatrix());
            customShader = StaticModelShader.state() != null;
            started = true;
        }
        pose.pushPose();
        try {
            var offset = instance.offset();
            pose.translate(offset.x(), offset.y(), offset.z());
            var snapshot = pose.last().copy();
            var gpu = instance.model().gpu();
            for (var part : selection.parts()) for (var batch : part.batches()) {
                draws.add(new Draw(batch, customShader && gpu != null ? gpu.part(batch) : null, snapshot, light));
            }
        } finally {
            pose.popPose();
        }
    }

    public void drawOpaque() {
        RenderSystem.assertOnRenderThread();
        if (draws.isEmpty()) return;
        try (var state = new ModelRenderState()) {
            for (var draw : draws) {
                var category = draw.batch().category();
                if (category == RenderCategory.BLEND || (category == RenderCategory.MIXED && !customShader)) continue;
                int alphaPass = draw.batch().alphaPass(false);
                state.use(ModelRenderTypes.get(draw.batch().texture(), draw.batch().doubleSided(), false,
                        alphaPass == StaticModelShader.FORCE_OPAQUE), false, draw.batch().doubleSided());
                if (draw.gpu() != null) {
                    var part = draw.gpu();
                    state.prepareGpu(draw.pose(), draw.light(), alphaPass, modelView, projection);
                    part.mesh().draw(false, part.firstIndex(), draw.batch().mesh().indices().length);
                } else {
                    state.endGpu();
                    drawFallback(draw, alphaPass, 0, draw.batch().mesh().indices().length / 3, false);
                }
            }
        } catch (RuntimeException | Error e) {
            clear();
            throw e;
        }
    }

    public void drawTranslucent() {
        RenderSystem.assertOnRenderThread();
        try {
            collectTriangles();
            if (triangles == 0) return;
            sortTriangles();
            try (var state = new ModelRenderState()) {
                prepareGpuIndices();
                for (int start = 0; start < triangles;) {
                    int drawIndex = triangleDraw[order[start]];
                    var draw = draws.get(drawIndex);
                    int end = start + 1;
                    while (end < triangles && canMerge(draw, draws.get(triangleDraw[order[end]]))) end++;
                    int alphaPass = draw.batch().alphaPass(true);
                    state.use(ModelRenderTypes.get(draw.batch().texture(), draw.batch().doubleSided(), true, false), true, draw.batch().doubleSided());
                    if (draw.gpu() != null) {
                        state.prepareGpu(draw.pose(), draw.light(), alphaPass, modelView, projection);
                        draw.gpu().mesh().draw(true, gpuOffset[start], (end - start) * 3);
                    } else {
                        state.endGpu();
                        drawFallback(draw, alphaPass, start, end, true);
                    }
                    start = end;
                }
            }
        } finally {
            clear();
        }
    }

    /** Only adjacent triangles with the same alpha policy, GPU mesh and instance can join. */
    private static boolean canMerge(Draw first, Draw next) {
        if (first == next) return true;
        return first.gpu() != null && next.gpu() != null && first.gpu().mesh() == next.gpu().mesh()
                && first.pose() == next.pose() && first.light() == next.light()
                && first.batch().alphaPass(true) == next.batch().alphaPass(true)
                && first.batch().doubleSided() == next.batch().doubleSided()
                && first.batch().texture().equals(next.batch().texture());
    }

    private void collectTriangles() {
        triangles = 0;
        for (int d = 0; d < draws.size(); d++) {
            var draw = draws.get(d);
            if (draw.batch().category() == RenderCategory.CUTOUT) continue;
            float[] centers = draw.batch().centers();
            ensureCapacity(Math.addExact(triangles, centers.length / 3));
            var viewPose = new Matrix4f(modelView).mul(draw.pose().pose());
            for (int i = 0; i < centers.length; i += 3) {
                triangleDraw[triangles] = d;
                triangleIndex[triangles] = i / 3;
                depth[triangles] = -(viewPose.m02() * centers[i] + viewPose.m12() * centers[i + 1]
                        + viewPose.m22() * centers[i + 2] + viewPose.m32());
                order[triangles] = triangles;
                triangles++;
            }
        }
    }

    private void ensureCapacity(int size) {
        if (order.length >= size) return;
        int capacity = Math.max(size, Math.max(256, order.length * 2));
        triangleDraw = Arrays.copyOf(triangleDraw, capacity);
        triangleIndex = Arrays.copyOf(triangleIndex, capacity);
        depth = Arrays.copyOf(depth, capacity);
        order = Arrays.copyOf(order, capacity);
        sortScratch = new int[capacity];
        gpuOffset = new int[capacity];
    }

    /** Stable merge sort: equal depth retains source/instance/triangle submission order. */
    private void sortTriangles() {
        for (int width = 1; width < triangles; width = width > triangles / 2 ? triangles : width * 2) {
            for (int start = 0; start < triangles;) {
                int mid = (int)Math.min((long)triangles, (long)start + width);
                int end = (int)Math.min((long)triangles, (long)mid + width);
                int left = start, right = mid;
                for (int out = start; out < end; out++) {
                    if (right == end || (left < mid && depth[order[left]] >= depth[order[right]])) {
                        sortScratch[out] = order[left++];
                    } else {
                        sortScratch[out] = order[right++];
                    }
                }
                start = end;
            }
            int[] swap = order; order = sortScratch; sortScratch = swap;
        }
    }

    private void prepareGpuIndices() {
        for (int i = 0; i < triangles; i++) {
            int triangle = order[i];
            var draw = draws.get(triangleDraw[triangle]);
            if (draw.gpu() == null) continue;
            var mesh = draw.gpu().mesh();
            if (sortedMeshes.put(mesh, Boolean.TRUE) == null) mesh.beginSortedIndices();
            gpuOffset[i] = mesh.appendTriangle(draw.batch().mesh().indices(), triangleIndex[triangle]);
        }
        sortedMeshes.keySet().forEach(GpuModel.Mesh::uploadSortedIndices);
    }

    /** CPU submission is used only when upload or shader loading failed; triangles are never sorted a second time. */
    private void drawFallback(Draw draw, int alphaPass, int start, int end, boolean sorted) {
        if (!customShader && alphaPass == StaticModelShader.FORCE_CUTOUT) StaticModelShader.warnCutoutFallback();
        if (fallbackBuffer == null) fallbackBuffer = new ByteBufferBuilder(1 << 18);
        var format = customShader ? StaticModelShader.FORMAT : DefaultVertexFormat.NEW_ENTITY;
        var builder = new BufferBuilder(fallbackBuffer, VertexFormat.Mode.TRIANGLES, format);
        int[] indices = draw.batch().mesh().indices();
        float[] vertices = draw.batch().mesh().vertices();
        int[] colors = draw.batch().mesh().colors();
        var matrix = draw.pose().pose();
        for (int t = start; t < end; t++) {
            int triangle = sorted ? triangleIndex[order[t]] : t;
            for (int corner = 0; corner < 3; corner++) {
                int v = indices[triangle * 3 + corner], i = v * 8;
                float x = vertices[i], y = vertices[i + 1], z = vertices[i + 2];
                float nx = vertices[i + 5], ny = vertices[i + 6], nz = vertices[i + 7];
                if (!customShader) {
                    float px = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
                    float py = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
                    z = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
                    x = px; y = py;
                    draw.pose().transformNormal(nx, ny, nz, normal);
                    nx = normal.x; ny = normal.y; nz = normal.z;
                }
                builder.addVertex(x, y, z, colors[v], vertices[i + 3], vertices[i + 4],
                        OverlayTexture.NO_OVERLAY, draw.light(), nx, ny, nz);
            }
        }
        var shader = RenderSystem.getShader();
        if (customShader) {
            var custom = StaticModelShader.state();
            custom.prepare(draw.pose(), draw.light(), alphaPass);
            shader = custom.shader();
        }
        shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        try {
            BufferUploader.draw(builder.buildOrThrow());
        } finally {
            shader.clear();
        }
    }

    public void clear() {
        draws.clear();
        sortedMeshes.clear();
        triangles = 0;
        started = false;
    }

    @Override
    public void close() {
        clear();
        if (fallbackBuffer != null) {
            fallbackBuffer.close();
            fallbackBuffer = null;
        }
    }
}
