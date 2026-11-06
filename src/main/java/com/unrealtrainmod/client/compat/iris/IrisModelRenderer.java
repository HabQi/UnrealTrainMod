/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.model.PartSelection;
import com.unrealtrainmod.client.renderer.ModelDrawSink;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import com.unrealtrainmod.compat.rtm.script.VehicleScriptManager;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

/** One local command snapshot per entity/frame; GPU geometry is independent of entity and view. */
public final class IrisModelRenderer {
    private record Command(RenderBatch batch, PoseStack.Pose pose, int light) {}
    private record Prepared(IrisBatch material, PoseStack.Pose pose, Matrix4f view, int light, IrisGeometryCache.Mesh gpu) {}
    private static final class Snapshot implements ModelDrawSink {
        private final List<Command> commands = new ArrayList<>();
        private final List<Prepared> prepared = new ArrayList<>();
        private final SortBuffer sorted = new SortBuffer();
        private boolean ready, poseReady;
        private VehiclePose vehiclePose;
        @Override public void submitGeometry(LoadedModel.Source source, PartSelection selection,
                                             PoseStack.Pose pose, int light, boolean directionalLighting) {
            var local = pose.copy();
            for (var batch : selection.batches()) commands.add(new Command(batch, local, light));
        }
    }
    /** Stable ascending view-Z sort; no objects are allocated per triangle. */
    private static final class SortBuffer {
        private int[] command = new int[0], triangle = new int[0], order = new int[0], scratch = new int[0];
        private float[] depth = new float[0];
        private int count;
        void add(int commandIndex, int triangleIndex, float z) {
            if (count == order.length) {
                int capacity = Math.max(256, count * 2);
                command = Arrays.copyOf(command, capacity); triangle = Arrays.copyOf(triangle, capacity);
                order = Arrays.copyOf(order, capacity); scratch = new int[capacity]; depth = Arrays.copyOf(depth, capacity);
            }
            command[count] = commandIndex; triangle[count] = triangleIndex; depth[count] = z; order[count] = count;
            count++;
        }
        void sort() {
            for (int width = 1; width < count; width = width > count / 2 ? count : width * 2) {
                for (int start = 0; start < count;) {
                    int middle = (int)Math.min((long)count, (long)start + width);
                    int end = (int)Math.min((long)count, (long)middle + width);
                    int left = start, right = middle;
                    for (int out = start; out < end; out++) {
                        if (right == end || left < middle && depth[order[left]] <= depth[order[right]]) scratch[out] = order[left++];
                        else scratch[out] = order[right++];
                    }
                    start = end;
                }
                int[] swap = order; order = scratch; scratch = swap;
            }
        }
    }
    private static final IdentityHashMap<Entity, Snapshot> ENTITIES = new IdentityHashMap<>();
    private IrisModelRenderer() {}

    public static VehiclePose vehiclePose(TrainEntity entity, float partialTick) {
        var snapshot = ENTITIES.computeIfAbsent(entity, ignored -> new Snapshot());
        if (!snapshot.poseReady) {
            snapshot.vehiclePose = entity.renderPose(partialTick);
            snapshot.poseReady = true;
        }
        return snapshot.vehiclePose;
    }

    public static void render(Entity identity, LoadedModel model, VehiclePose vehiclePose, TrainEntity train,
                              float partialTick, PoseStack viewPose, MultiBufferSource buffers, int light) {
        boolean shadow = IrisCompat.shadowPass();
        try (var metrics = IrisRenderStats.begin(IrisRenderStats.VEHICLE, shadow)) {
            long start = IrisRenderStats.clock();
            var snapshot = ENTITIES.computeIfAbsent(identity, ignored -> new Snapshot());
            if (!snapshot.ready) {
                TrainModelRenderer.submit(snapshot, model, vehiclePose, train, partialTick, new PoseStack(), light);
                snapshot.ready = true;
            }
            prepare(snapshot, viewPose, shadow);
            IrisRenderStats.prepare(start);
            draw(snapshot, buffers, shadow);
        }
    }

    public static boolean renderPart(LoadedModel.Instance instance, String name, PoseStack pose, MultiBufferSource buffers, int light) {
        var selection = instance.model().partsByName().get(name);
        if (selection == null) return false;
        boolean shadow = IrisCompat.shadowPass();
        try (var metrics = IrisRenderStats.begin(IrisRenderStats.VEHICLE, shadow)) {
            long start = IrisRenderStats.clock();
            var snapshot = new Snapshot();
            snapshot.submit(instance, selection, new PoseStack(), light);
            prepare(snapshot, pose, shadow);
            IrisRenderStats.prepare(start);
            draw(snapshot, buffers, shadow);
        }
        return true;
    }

    private static void prepare(Snapshot snapshot, PoseStack viewPose, boolean shadow) {
        snapshot.prepared.clear();
        for (var command : snapshot.commands) {
            if (shadow && command.batch().category() == RenderCategory.BLEND) continue;
            var material = IrisBatch.get(command.batch());
            var posed = new PoseStack();
            posed.last().pose().set(viewPose.last().pose()).mul(command.pose().pose());
            posed.last().normal().set(viewPose.last().normal()).mul(command.pose().normal());
            var gpu = material.solidCount == 0 || !IrisGeometryCache.supportsPose(posed.last().pose()) ? null : IrisGeometryCache.requestVehicle(
                    material.solidMesh, material.firstSolid, material.solidCount, material.forceOpaque);
            if (material.solidCount != 0) material.solidType();
            snapshot.prepared.add(new Prepared(material, posed.last(),
                    new Matrix4f(RenderSystem.getModelViewMatrix()).mul(posed.last().pose()), command.light(), gpu));
        }
    }

    private static void draw(Snapshot snapshot, MultiBufferSource buffers, boolean shadow) {
        long start = IrisRenderStats.clock();
        // Complete the direct drawing range before any BufferSource getBuffer() can flush a wrapped batch.
        boolean cached = false;
        for (var draw : snapshot.prepared) if (draw.gpu() != null) { cached = true; break; }
        if (cached) try (var scope = IrisDrawScope.entity()) {
            for (var draw : snapshot.prepared) if (draw.gpu() != null) {
                scope.drawCached(draw.gpu(), draw.material().solidType(), draw.view(), RenderSystem.getProjectionMatrix(), draw.light());
                IrisRenderStats.gpu(draw.material().solidCount / 3);
            }
        }
        var writer = new IrisVertices();
        for (var draw : snapshot.prepared) {
            var material = draw.material();
            if (material.solidCount == 0 || draw.gpu() != null) continue;
            var target = buffers.getBuffer(material.solidType());
            for (int i = material.firstSolid; i < material.firstSolid + material.solidCount; i++) {
                writer.vertex(target, material.solidMesh, material.solidMesh.indices()[i], draw.pose(), draw.light(), material.forceOpaque);
            }
            IrisRenderStats.buffered(material.solidCount / 3);
        }
        IrisRenderStats.submit(start);
        if (shadow) return;
        start = IrisRenderStats.clock();
        var sorted = snapshot.sorted;
        sorted.count = 0;
        for (int d = 0; d < snapshot.prepared.size(); d++) {
            var draw = snapshot.prepared.get(d);
            var batch = draw.material().batch;
            if (batch.category() == RenderCategory.CUTOUT) continue;
            float[] centers = batch.centers();
            var m = draw.view();
            for (int i = 0; i < centers.length; i += 3) sorted.add(d, i / 3,
                    m.m02() * centers[i] + m.m12() * centers[i + 1] + m.m22() * centers[i + 2] + m.m32());
        }
        sorted.sort();
        IrisRenderStats.sort(start);
        start = IrisRenderStats.clock();
        for (int i = 0; i < sorted.count; i++) {
            int index = sorted.order[i];
            var draw = snapshot.prepared.get(sorted.command[index]);
            var material = draw.material(); var batch = material.batch;
            int triangle = sorted.triangle[index], offset = batch.firstIndex() + triangle * 3;
            var target = buffers.getBuffer(material.translucentType(triangle));
            for (int corner = 0; corner < 3; corner++) writer.vertex(target, batch.mesh(),
                    batch.mesh().indices()[offset + corner], draw.pose(), draw.light(), false);
        }
        IrisRenderStats.buffered(sorted.count);
        IrisRenderStats.submit(start);
    }

    public static void beginFrame() {
        var iterator = ENTITIES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var snapshot = entry.getValue();
            if (entry.getKey().isRemoved() || entry.getKey().level() != Minecraft.getInstance().level) {
                VehicleScriptManager.releasePreview(snapshot);
                iterator.remove();
            } else {
                snapshot.commands.clear(); snapshot.prepared.clear();
                snapshot.ready = snapshot.poseReady = false;
                snapshot.vehiclePose = null;
            }
        }
    }

    public static void clear() {
        ENTITIES.values().forEach(VehicleScriptManager::releasePreview);
        ENTITIES.clear();
        IrisRenderStats.reset();
        IrisGeometryCache.clear();
    }
}
