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
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.StaticModelShader;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.model.PartSelection;
import com.unrealtrainmod.compat.rtm.script.VehicleScriptManager;
import com.unrealtrainmod.client.renderer.instancing.InstanceBatchKey;
import com.unrealtrainmod.client.renderer.instancing.InstanceBuffer;
import com.unrealtrainmod.client.renderer.instancing.InstancedModelShader;
import com.unrealtrainmod.client.renderer.instancing.InstancingStats;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One world view or one preview. No per-triangle objects and no vertex uploads on the normal GPU path. */
public final class ModelRenderQueue implements ModelDrawSink, AutoCloseable {
    private record Draw(RenderBatch batch, GpuModel.Part gpu, PoseStack.Pose pose, int light,
                        boolean directionalLighting) {}
    private static final class InstanceGroup {
        private final List<Draw> draws = new ArrayList<>();
        private int startInstance;
    }
    private final String scene;
    private final boolean frontLighting;
    private final List<Draw> draws = new ArrayList<>();
    private final IdentityHashMap<GpuModel.Mesh, Boolean> sortedMeshes = new IdentityHashMap<>();
    private final IdentityHashMap<GpuModel.Mesh, Boolean> instancedMeshes = new IdentityHashMap<>();
    private final InstanceBuffer instanceBuffer = new InstanceBuffer();
    private final InstancingStats stats = new InstancingStats();
    private final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f();
    private final Vector3f normal = new Vector3f();
    private int[] triangleDraw = new int[0], triangleIndex = new int[0], order = new int[0];
    private int[] sortScratch = new int[0], gpuOffset = new int[0];
    private float[] depth = new float[0];
    private int triangles;
    private boolean started, customShader, collectStats, statsFrameStarted;
    private ByteBufferBuilder fallbackBuffer;

    public ModelRenderQueue() { this("world", false); }

    public ModelRenderQueue(String scene) { this(scene, false); }

    /** Front lighting is reserved for GUI vehicle previews; world queues retain scene lighting. */
    public ModelRenderQueue(String scene, boolean frontLighting) {
        this.scene = scene;
        this.frontLighting = frontLighting;
    }

    /** Latch the diagnostic switch before submission so pose, script and enqueue scopes share one frame. */
    public InstancingStats statistics() {
        if (!statsFrameStarted) {
            statsFrameStarted = true;
            collectStats = ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean();
            if (collectStats) stats.beginFrame();
            else stats.close();
        }
        return collectStats ? stats : null;
    }

    public void resetStatistics() { stats.close(); statsFrameStarted = collectStats = false; }

    public void submit(LoadedModel model, PoseStack pose, int light) {
        for (var instance : model.instances()) submit(instance, instance.visibleParts(), pose, light);
    }

    /** Geometry-only submission for a caller that already applied a component's complete transform. */
    public void submitGeometry(LoadedModel.Source source, PartSelection selection, PoseStack pose, int light) {
        submitGeometry(source, selection, pose.last(), light);
    }

    /** Records an already captured pose so script commands can be discarded until the script succeeds. */
    public void submitGeometry(LoadedModel.Source source, PartSelection selection, PoseStack.Pose pose, int light) {
        submitGeometry(source, selection, pose, light, true);
    }

    /** Script lighting is captured per draw; existing geometry submissions keep directional lighting. */
    public void submitGeometry(LoadedModel.Source source, PartSelection selection, PoseStack.Pose pose, int light,
                               boolean directionalLighting) {
        RenderSystem.assertOnRenderThread();
        var frameStats = statistics();
        long enqueueStart = frameStats == null ? 0L : System.nanoTime();
        if (!started) {
            modelView.set(RenderSystem.getModelViewMatrix());
            projection.set(RenderSystem.getProjectionMatrix());
            customShader = StaticModelShader.state() != null;
            started = true;
        }
        var snapshot = pose.copy();
        var gpu = source.gpu();
        for (var batch : selection.batches()) {
            draws.add(new Draw(batch, customShader && gpu != null ? gpu.part(batch) : null, snapshot, light,
                    directionalLighting));
        }
        if (frameStats != null) frameStats.addEnqueue(System.nanoTime() - enqueueStart);
    }

    public void drawOpaque() {
        RenderSystem.assertOnRenderThread();
        var frameStats = statistics();
        if (draws.isEmpty()) return;
        if (frameStats != null) frameStats.beginGpu(true);
        long opaqueStart = collectStats ? System.nanoTime() : 0L;
        boolean completed = false;
        try (var state = new ModelRenderState(frontLighting, collectStats)) {
            boolean instancing = instancingEnabled();
            if (instancing) drawOpaqueInstanced(state);
            else drawOpaqueDirect(state);
            if (collectStats) stats.addShaderCounts(state.shaderBinds(), state.shaderSwitches());
            completed = true;
        } catch (RuntimeException | Error e) {
            completed = false;
            clear();
            throw e;
        } finally {
            // Includes state capture, GL submission, instance-reference cleanup and caller-state restoration.
            if (frameStats != null) {
                frameStats.addOpaque(System.nanoTime() - opaqueStart);
                frameStats.endGpu(true, completed);
            }
        }
    }

    private void drawOpaqueDirect(ModelRenderState state) {
        for (var draw : draws) {
            if (!drawsOpaque(draw)) continue;
            if (collectStats) {
                stats.request();
                if (instanceable(draw)) stats.instanceable();
            }
            drawOpaqueSingle(state, draw);
        }
    }

    private void drawOpaqueSingle(ModelRenderState state, Draw draw) {
        var batch = draw.batch();
        int alphaPass = batch.alphaPass(false);
        state.use(ModelRenderTypes.get(batch.texture(), batch.doubleSided(), false,
                alphaPass == StaticModelShader.FORCE_OPAQUE), false, batch.doubleSided());
        if (draw.gpu() != null) {
            var part = draw.gpu();
            state.prepareGpu(draw.pose(), draw.light(), alphaPass, draw.directionalLighting(), modelView, projection);
            part.mesh().draw(false, part.firstIndex(), batch.indexCount());
            if (collectStats) stats.gpuDraw(batch.indexCount());
        } else {
            state.endGpu();
            drawFallback(state, draw, alphaPass, 0, batch.indexCount() / 3, false);
            if (collectStats) stats.fallbackDraw(batch.indexCount());
        }
    }

    /**
     * CUTOUT requests are grouped per run of consecutive drawable requests sharing the same GPU geometry and
     * material state. A drawable request that cannot be instanced ends the run, so fallback and ordered draws
     * keep their place; BLEND requests produce nothing here and therefore do not split batches.
     */
    private void drawOpaqueInstanced(ModelRenderState state) {
        long groupingStart = collectStats ? System.nanoTime() : 0L;
        var steps = new ArrayList<Object>();
        var run = new LinkedHashMap<InstanceBatchKey, InstanceGroup>();
        for (var draw : draws) {
            if (!drawsOpaque(draw)) continue;
            if (collectStats) stats.request();
            if (instanceable(draw)) {
                if (collectStats) stats.instanceable();
                run.computeIfAbsent(instanceKey(draw), key -> new InstanceGroup()).draws.add(draw);
            } else {
                flushRun(run, steps);
                steps.add(draw);
            }
        }
        flushRun(run, steps);
        int instances = 0;
        for (var step : steps) {
            if (step instanceof InstanceGroup group) {
                group.startInstance = instances;
                instances += group.draws.size();
            }
        }
        if (collectStats) stats.addGrouping(System.nanoTime() - groupingStart);
        if (steps.isEmpty()) return;
        if (instances > 0) {
            long packingStart = collectStats ? System.nanoTime() : 0L;
            instanceBuffer.begin();
            for (var step : steps) {
                if (step instanceof InstanceGroup group) {
                    for (var draw : group.draws) instanceBuffer.add(draw.pose(), draw.light());
                }
            }
            long uploadStart = collectStats ? System.nanoTime() : 0L;
            if (collectStats) stats.addPacking(uploadStart - packingStart);
            instanceBuffer.upload();
            if (collectStats) stats.addUpload(instanceBuffer.uploadedBytes(), System.nanoTime() - uploadStart);
        }

        int[] attributes = InstancedModelShader.state().attributes();
        try {
            for (var step : steps) {
                if (step instanceof InstanceGroup group) drawInstanceGroup(state, group, attributes);
                else drawOpaqueSingle(state, (Draw)step);
            }
        } finally {
            try {
                instancedMeshes.keySet().forEach(GpuModel.Mesh::releaseInstanceBuffer);
            } finally {
                instancedMeshes.clear();
            }
        }
    }

    private static void flushRun(LinkedHashMap<InstanceBatchKey, InstanceGroup> run, List<Object> steps) {
        for (var group : run.values()) {
            // A single request keeps the existing direct GPU draw instead of paying instancing overhead.
            if (group.draws.size() >= 2) steps.add(group);
            else steps.add(group.draws.getFirst());
        }
        run.clear();
    }

    private void drawInstanceGroup(ModelRenderState state, InstanceGroup group, int[] attributes) {
        var first = group.draws.getFirst();
        var batch = first.batch();
        int alphaPass = batch.alphaPass(false);
        state.use(ModelRenderTypes.get(batch.texture(), batch.doubleSided(), false,
                alphaPass == StaticModelShader.FORCE_OPAQUE), false, batch.doubleSided());
        state.prepareInstanced(alphaPass, first.directionalLighting(), modelView, projection);
        var part = first.gpu();
        instancedMeshes.put(part.mesh(), Boolean.TRUE);
        part.mesh().drawInstanced(part.firstIndex(), batch.indexCount(), instanceBuffer.handle(),
                instanceBuffer.offsetOf(group.startInstance), group.draws.size(), attributes);
        if (collectStats) stats.instancedDraw(group.draws.size(), batch.indexCount());
    }

    private static InstanceBatchKey instanceKey(Draw draw) {
        var part = draw.gpu();
        var batch = draw.batch();
        return new InstanceBatchKey(part.mesh(), part.firstIndex(), batch.indexCount(), batch.texture(),
                batch.doubleSided(), batch.alphaPass(false), draw.directionalLighting());
    }

    private boolean drawsOpaque(Draw draw) {
        var category = draw.batch().category();
        return category == RenderCategory.CUTOUT || (category == RenderCategory.MIXED && customShader);
    }

    private boolean instanceable(Draw draw) {
        return customShader && draw.batch().category() == RenderCategory.CUTOUT && draw.gpu() != null
                && InstancedModelShader.state() != null;
    }

    private boolean instancingEnabled() {
        return ClientConfig.USE_VEHICLE_INSTANCING.getAsBoolean() && customShader && InstancedModelShader.usable();
    }

    public void drawTranslucent() {
        RenderSystem.assertOnRenderThread();
        var frameStats = statistics();
        if (frameStats != null) frameStats.beginGpu(false);
        long start = frameStats == null ? 0L : System.nanoTime();
        boolean completed = false;
        try {
            drawTranslucentPass();
            completed = true;
        } finally {
            try {
                if (frameStats != null) {
                    frameStats.addTranslucent(System.nanoTime() - start);
                    frameStats.endGpu(false, completed);
                    if (completed) frameStats.endFrame(scene);
                }
            } finally { clear(); }
        }
    }

    private void drawTranslucentPass() {
        if (ClientConfig.USE_STATIC_TRANSLUCENT_BATCHES.getAsBoolean()) {
            drawStaticTranslucent();
            return;
        }
        collectTriangles();
        if (triangles == 0) return;
        if (ClientConfig.SORT_TRANSLUCENT_TRIANGLES.getAsBoolean()) sortTriangles();
        try (var state = new ModelRenderState(frontLighting)) {
            prepareGpuIndices();
            for (int start = 0; start < triangles;) {
                int drawIndex = triangleDraw[order[start]];
                var draw = draws.get(drawIndex);
                int end = start + 1;
                while (end < triangles && canMerge(draw, draws.get(triangleDraw[order[end]]))) end++;
                int alphaPass = draw.batch().alphaPass(true);
                state.use(ModelRenderTypes.get(draw.batch().texture(), draw.batch().doubleSided(), true, false), true, draw.batch().doubleSided());
                if (draw.gpu() != null) {
                    state.prepareGpu(draw.pose(), draw.light(), alphaPass, draw.directionalLighting(), modelView, projection);
                    draw.gpu().mesh().draw(true, gpuOffset[start], (end - start) * 3);
                } else {
                    state.endGpu();
                    drawFallback(state, draw, alphaPass, start, end, true);
                }
                if (collectStats) stats.translucentDraw(end - start);
                start = end;
            }
        }
    }

    /** Submission-order comparison path: GPU draws reuse canonical IBO ranges without collecting triangles. */
    private void drawStaticTranslucent() {
        int start = 0;
        while (start < draws.size() && draws.get(start).batch().category() == RenderCategory.CUTOUT) start++;
        if (start == draws.size()) return;
        try (var state = new ModelRenderState(frontLighting)) {
            while (start < draws.size()) {
                var draw = draws.get(start);
                if (draw.batch().category() == RenderCategory.CUTOUT) {
                    start++;
                    continue;
                }
                int end = start + 1;
                int count = draw.batch().indexCount();
                if (draw.gpu() != null) {
                    while (end < draws.size()) {
                        var next = draws.get(end);
                        if (next.batch().category() == RenderCategory.CUTOUT || !canMerge(draw, next)
                                || next.gpu().firstIndex() != draw.gpu().firstIndex() + count) break;
                        count += next.batch().indexCount();
                        end++;
                    }
                }
                int alphaPass = draw.batch().alphaPass(true);
                state.use(ModelRenderTypes.get(draw.batch().texture(), draw.batch().doubleSided(), true, false),
                        true, draw.batch().doubleSided());
                if (draw.gpu() != null) {
                    state.prepareGpu(draw.pose(), draw.light(), alphaPass, draw.directionalLighting(), modelView, projection);
                    draw.gpu().mesh().draw(false, draw.gpu().firstIndex(), count);
                } else {
                    state.endGpu();
                    drawFallback(state, draw, alphaPass, 0, count / 3, false);
                }
                if (collectStats) stats.translucentDraw(count / 3);
                start = end;
            }
        }
    }

    /** Only adjacent triangles with the same alpha policy, GPU mesh and instance can join. */
    private static boolean canMerge(Draw first, Draw next) {
        if (first == next) return true;
        return first.gpu() != null && next.gpu() != null && first.gpu().mesh() == next.gpu().mesh()
                && first.pose() == next.pose() && first.light() == next.light()
                && first.directionalLighting() == next.directionalLighting()
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
            gpuOffset[i] = mesh.appendTriangle(draw.batch().mesh().indices(), draw.batch().firstIndex() / 3 + triangleIndex[triangle]);
        }
        sortedMeshes.keySet().forEach(GpuModel.Mesh::uploadSortedIndices);
    }

    /** CPU submission is used only when upload or shader loading failed; triangles are never sorted a second time. */
    private void drawFallback(ModelRenderState state, Draw draw, int alphaPass, int start, int end, boolean sorted) {
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
                int v = indices[draw.batch().firstIndex() + triangle * 3 + corner], i = v * 8;
                float x = vertices[i], y = vertices[i + 1], z = vertices[i + 2];
                float nx = vertices[i + 5], ny = vertices[i + 6], nz = vertices[i + 7];
                if (!customShader) {
                    float px = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
                    float py = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
                    z = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
                    x = px; y = py;
                    if (draw.directionalLighting()) {
                        draw.pose().transformNormal(nx, ny, nz, normal);
                        nx = normal.x; ny = normal.y; nz = normal.z;
                    } else {
                        nx = 0; ny = 0; nz = 1;
                    }
                }
                builder.addVertex(x, y, z, colors[v], vertices[i + 3], vertices[i + 4],
                        OverlayTexture.NO_OVERLAY, draw.light(), nx, ny, nz);
            }
        }
        var shader = RenderSystem.getShader();
        if (customShader) {
            var custom = StaticModelShader.state();
            custom.prepare(draw.pose(), draw.light(), alphaPass, draw.directionalLighting());
            shader = custom.shader();
        }
        shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
        try {
            state.setupLights(shader, customShader || draw.directionalLighting());
            shader.apply();
            BufferUploader.draw(builder.buildOrThrow());
        } finally {
            try {
                shader.clear();
            } finally {
                state.restoreLights(shader, customShader || draw.directionalLighting());
            }
        }
    }

    public void clear() {
        draws.clear();
        sortedMeshes.clear();
        triangles = 0;
        started = false;
        statsFrameStarted = collectStats = false;
    }

    @Override
    public void close() {
        VehicleScriptManager.releasePreview(this);
        clear();
        stats.close();
        instanceBuffer.close();
        if (fallbackBuffer != null) {
            fallbackBuffer.close();
            fallbackBuffer = null;
        }
    }
}
