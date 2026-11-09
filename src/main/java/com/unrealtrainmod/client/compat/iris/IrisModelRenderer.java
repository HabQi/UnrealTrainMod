/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.model.PartSelection;
import com.unrealtrainmod.client.renderer.ModelDrawSink;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.renderer.SurfaceLighting;
import com.unrealtrainmod.client.renderer.IrFlareRenderer;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import com.unrealtrainmod.compat.rtm.script.VehicleScriptManager;
import com.unrealtrainmod.compat.ir.IrLightingDefinition;
import net.minecraft.resources.ResourceLocation;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.vehicle.VehiclePose;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/** One local command snapshot per entity/frame; GPU geometry is independent of entity and view. */
public final class IrisModelRenderer {
    private static final class Command {
        private RenderBatch batch;
        private PoseStack.Pose pose;
        private int light;
        private float emission;

        void set(RenderBatch batch, PoseStack.Pose pose, int light, float emission) {
            this.batch = batch;
            this.pose = pose;
            this.light = light;
            this.emission = emission;
        }
        RenderBatch batch() { return batch; }
        PoseStack.Pose pose() { return pose; }
        int light() { return light; }
        void clear() { batch = null; pose = null; }
    }
    private static final class Prepared {
        private IrisBatch material;
        private PoseStack.Pose pose;
        private Matrix4f view;
        private int light;
        private IrisGeometryCache.Mesh solidGpu, clippedGpu, lightGpu;

        void set(IrisBatch material, PoseStack.Pose pose, Matrix4f view, int light,
                 IrisGeometryCache.Mesh solidGpu, IrisGeometryCache.Mesh clippedGpu, IrisGeometryCache.Mesh lightGpu) {
            this.material = material;
            this.pose = pose;
            this.view = view;
            this.light = light;
            this.solidGpu = solidGpu;
            this.clippedGpu = clippedGpu;
            this.lightGpu = lightGpu;
        }
        IrisBatch material() { return material; }
        PoseStack.Pose pose() { return pose; }
        Matrix4f view() { return view; }
        int light() { return light; }
        IrisGeometryCache.Mesh solidGpu() { return solidGpu; }
        IrisGeometryCache.Mesh clippedGpu() { return clippedGpu; }
        IrisGeometryCache.Mesh lightGpu() { return lightGpu; }
        void clear() {
            material = null;
            pose = null;
            view = null;
            solidGpu = clippedGpu = lightGpu = null;
        }
    }
    /** Owned matrices are not reused until the next frame, after deferred transparency is discarded. */
    private static final class ViewPose {
        private final PoseStack.Pose pose = new PoseStack().last();
        private final Matrix4f view = new Matrix4f();

        void set(PoseStack.Pose viewPose, PoseStack.Pose local) {
            pose.pose().set(viewPose.pose()).mul(local.pose());
            pose.normal().set(viewPose.normal()).mul(local.normal());
            view.set(RenderSystem.getModelViewMatrix()).mul(pose.pose());
        }
    }
    private static final Comparator<Prepared> GPU_RANGE_ORDER = Comparator
            .comparingLong((Prepared draw) -> draw.solidGpu().storage.order)
            .thenComparingInt(draw -> draw.solidGpu().firstVertex)
            .thenComparingInt(draw -> draw.solidGpu().vertices);
    private static final class OpaqueCommand {
        Prepared draw;
        RenderType type;
        long state;
        int first, vertices;
        void set(Prepared draw, RenderType type, long state) {
            this.draw = draw;
            this.type = type;
            this.state = state;
            first = draw.solidGpu().firstVertex;
            vertices = draw.solidGpu().vertices;
        }
    }
    /** View matrices are immutable for the frame; inserted keys retain a cached full-value hash. */
    private static final class PoseKey {
        private Matrix4f view;
        private int hash, id;

        void lookup(Matrix4f view) {
            this.view = view;
            hash = view.hashCode();
        }
        void set(PoseKey lookup, int id) {
            view = lookup.view;
            hash = lookup.hash;
            this.id = id;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof PoseKey key && view.equals(key.view);
        }
    }
    private static final class MaterialGroup {
        private final Long2ObjectOpenHashMap<List<Prepared>> states = new Long2ObjectOpenHashMap<>();
        private final List<List<Prepared>> lists = new ArrayList<>();
        private long[] order = new long[0];
        private int count, poseRuns, lastPose = -1;

        void add(Prepared draw, int pose, boolean diagnostics) {
            if (diagnostics) {
                if (lastPose != pose) poseRuns++;
                lastPose = pose;
            }
            long state = ((long) pose << 32) | (draw.light() & 0xffffffffL);
            var group = states.get(state);
            if (group == null) {
                if (count == lists.size()) lists.add(new ArrayList<>());
                if (count == order.length) order = Arrays.copyOf(order, Math.max(4, count * 2));
                order[count] = state;
                group = lists.get(count++);
                states.put(state, group);
            }
            group.add(draw);
        }
        int sort(boolean diagnostics) {
            if (count > 1) Arrays.sort(order, 0, count);
            if (!diagnostics) return 0;
            int poses = 0, previous = -1;
            for (int i = 0; i < count; i++) {
                int pose = (int) (order[i] >>> 32);
                if (pose != previous) poses++;
                previous = pose;
            }
            return poses;
        }
        void clear() {
            states.clear();
            for (int i = 0; i < count; i++) lists.get(i).clear();
            count = poseRuns = 0;
            lastPose = -1;
        }
    }
    /** Sample existing opaque groups only; no vertex reads, triangle walks or changes to draw order. */
    private static final class RangeDiagnostics {
        private record VertexSource(float[] vertices, int[] colors) {}
        private static final Comparator<Prepared> RANGE_ORDER = Comparator
                .comparingInt((Prepared draw) -> draw.material().firstSolid)
                .thenComparingInt(draw -> draw.material().solidCount);
        private final IdentityHashMap<IrisGeometryCache.Storage, Boolean> buffers = new IdentityHashMap<>();
        private final HashSet<VertexSource> vertices = new HashSet<>();
        private final IdentityHashMap<IndexedMesh, List<Prepared>> sources = new IdentityHashMap<>();
        private final List<List<Prepared>> sourceLists = new ArrayList<>();
        private int sourceCount;

        void inspect(Iterable<MaterialGroup> materials, IrisRenderStats.RangeSample sample, long start) {
            try {
                for (var material : materials) for (int i = 0; i < material.count; i++) {
                    var draws = material.states.get(material.order[i]);
                    sample.stateGroups++;
                    sample.requests += draws.size();
                    sample.maxGroup = Math.max(sample.maxGroup, draws.size());
                    if (draws.size() > 1) { sample.multiGroups++; sample.multiRequests += draws.size(); }
                    try {
                        for (var draw : draws) {
                            var batch = draw.material();
                            sample.triangles += batch.solidCount / 3;
                            buffers.put(draw.solidGpu().storage, Boolean.TRUE);
                            var mesh = batch.solidMesh;
                            vertices.add(new VertexSource(mesh.vertices(), mesh.colors()));
                            var ranges = sources.get(mesh);
                            if (ranges == null) {
                                if (sourceCount == sourceLists.size()) sourceLists.add(new ArrayList<>());
                                ranges = sourceLists.get(sourceCount++);
                                sources.put(mesh, ranges);
                            }
                            ranges.add(draw);
                        }
                        sample.bufferGroups += buffers.size();
                        sample.sourceGroups += sourceCount;
                        sample.vertexSets += vertices.size();
                        for (var ranges : sources.values()) inspectSource(ranges, sample);
                    } finally {
                        buffers.clear();
                        vertices.clear();
                        sources.clear();
                        for (int j = 0; j < sourceCount; j++) sourceLists.get(j).clear();
                        sourceCount = 0;
                    }
                }
            } finally {
                sample.nanos = System.nanoTime() - start;
                IrisRenderStats.rangeDiagnostics(sample.nanos);
            }
        }
        private static void inspectSource(List<Prepared> ranges, IrisRenderStats.RangeSample sample) {
            if (ranges.size() > 1) ranges.sort(RANGE_ORDER);
            int previousFirst = -1, previousCount = 0;
            long end = -1;
            for (var draw : ranges) {
                int first = draw.material().firstSolid, count = draw.material().solidCount;
                if (end >= 0 && first == end) sample.adjacentJoins++;
                else {
                    sample.contiguousRuns++;
                    if (end >= 0) {
                        if (first > end) sample.gapBreaks++;
                        else sample.overlapBreaks++;
                    }
                }
                if (first == previousFirst && count == previousCount) sample.duplicateRanges++;
                end = (long) first + count;
                previousFirst = first;
                previousCount = count;
            }
        }
    }
    private static final class Snapshot implements ModelDrawSink, AutoCloseable {
        private final List<Command> commands = new ArrayList<>();
        private final List<Prepared> prepared = new ArrayList<>();
        private final List<Command> commandPool = new ArrayList<>();
        private final List<Prepared> preparedPool = new ArrayList<>();
        private final List<ViewPose> viewPosePool = new ArrayList<>();
        private int commandCount, preparedCount, viewPoseCount;
        private final LinkedHashMap<RenderType, MaterialGroup> opaque = new LinkedHashMap<>();
        private final List<MaterialGroup> opaqueGroups = new ArrayList<>();
        private final IdentityHashMap<Matrix4f, PoseKey> opaqueSnapshots = new IdentityHashMap<>();
        private final HashMap<PoseKey, PoseKey> opaquePoses = new HashMap<>();
        private final List<PoseKey> opaquePosePool = new ArrayList<>();
        private final PoseKey opaqueLookup = new PoseKey();
        private int opaquePoseCount;
        private final List<OpaqueCommand> opaqueCommands = new ArrayList<>();
        private int opaqueCommandCount;
        private IntBuffer multiRanges;
        private RangeDiagnostics rangeDiagnostics;
        private final SortBuffer sorted = new SortBuffer();
        private boolean ready, poseReady;
        private VehiclePose vehiclePose;
        private int lights;
        private final List<IrFlareRenderer.Flare> flares = new ArrayList<>();

        private void addCommand(RenderBatch batch, PoseStack.Pose pose, int light, float emission) {
            if (commandCount == commandPool.size()) {
                commandPool.add(new Command());
                IrisRenderStats.commandPoolGrew();
            }
            var command = commandPool.get(commandCount++);
            command.set(batch, pose, light, emission);
            commands.add(command);
        }

        private void addPrepared(IrisBatch material, PoseStack.Pose pose, Matrix4f view, int light,
                                 IrisGeometryCache.Mesh solidGpu, IrisGeometryCache.Mesh clippedGpu,
                                 IrisGeometryCache.Mesh lightGpu) {
            if (preparedCount == preparedPool.size()) {
                preparedPool.add(new Prepared());
                IrisRenderStats.preparedPoolGrew();
            }
            var draw = preparedPool.get(preparedCount++);
            draw.set(material, pose, view, light, solidGpu, clippedGpu, lightGpu);
            prepared.add(draw);
        }

        private ViewPose viewPose(PoseStack.Pose view, PoseStack.Pose local) {
            if (viewPoseCount == viewPosePool.size()) {
                viewPosePool.add(new ViewPose());
                IrisRenderStats.viewPosePoolGrew();
            }
            var posed = viewPosePool.get(viewPoseCount++);
            posed.set(view, local);
            return posed;
        }

        private void resetFrame() {
            commands.clear();
            flares.clear();
            prepared.clear();
            for (int i = 0; i < commandCount; i++) commandPool.get(i).clear();
            for (int i = 0; i < preparedCount; i++) preparedPool.get(i).clear();
            commandCount = preparedCount = viewPoseCount = 0;
            ready = poseReady = false;
            vehiclePose = null;
        }

        private int opaquePose(Matrix4f view) {
            var pose = opaqueSnapshots.get(view);
            if (pose != null) return pose.id;
            opaqueLookup.lookup(view);
            pose = opaquePoses.get(opaqueLookup);
            if (pose == null) {
                if (opaquePoseCount == opaquePosePool.size()) opaquePosePool.add(new PoseKey());
                pose = opaquePosePool.get(opaquePoseCount);
                pose.set(opaqueLookup, opaquePoseCount++);
                opaquePoses.put(pose, pose);
            }
            opaqueSnapshots.put(view, pose);
            return pose.id;
        }
        private void clearOpaque(int groups) {
            for (int i = 0; i < opaqueCommandCount; i++) {
                var command = opaqueCommands.get(i);
                command.draw = null;
                command.type = null;
            }
            opaqueCommandCount = 0;
            opaque.clear();
            for (int i = 0; i < groups; i++) opaqueGroups.get(i).clear();
            opaqueSnapshots.clear();
            opaquePoses.clear();
            opaqueLookup.view = null;
            for (int i = 0; i < opaquePoseCount; i++) opaquePosePool.get(i).view = null;
            opaquePoseCount = 0;
        }
        private int mergeOpaque() {
            int before = 0;
            for (var material : opaque.entrySet()) {
                var group = material.getValue();
                for (int i = 0; i < group.count; i++) {
                    var draws = group.states.get(group.order[i]);
                    before += draws.size();
                    if (draws.size() > 1) draws.sort(GPU_RANGE_ORDER);
                    OpaqueCommand previous = null;
                    for (var draw : draws) {
                        var mesh = draw.solidGpu();
                        if (previous != null && previous.draw.solidGpu().storage == mesh.storage
                                && (long) previous.first + previous.vertices == mesh.firstVertex) {
                            previous.vertices += mesh.vertices;
                        } else {
                            if (opaqueCommandCount == opaqueCommands.size()) opaqueCommands.add(new OpaqueCommand());
                            previous = opaqueCommands.get(opaqueCommandCount++);
                            previous.set(draw, material.getKey(), group.order[i]);
                        }
                    }
                }
            }
            return before;
        }

        private void prepareRanges(int first, int end) {
            int count = end - first;
            int size = Math.multiplyExact(count, 2);
            if (multiRanges == null || multiRanges.capacity() < size) {
                var replacement = MemoryUtil.memAllocInt(Math.max(16, size));
                MemoryUtil.memFree(multiRanges);
                multiRanges = replacement;
            }
            multiRanges.clear();
            for (int i = 0; i < count; i++) {
                var command = opaqueCommands.get(first + i);
                multiRanges.put(i, command.first);
                multiRanges.put(count + i, command.vertices);
            }
        }

        @Override public void close() {
            try { VehicleScriptManager.releasePreview(this); }
            finally {
                MemoryUtil.memFree(multiRanges);
                multiRanges = null;
            }
        }
        @Override public boolean worldView() { return true; }
        @Override public int lights() { return lights; }
        @Override public void lights(int value) { lights = value; }
        @Override public void submitGeometry(LoadedModel.Source source, PartSelection selection,
                                             PoseStack.Pose pose, int light, boolean directionalLighting) {
            submitGeometry(source, selection, pose, light, directionalLighting, SurfaceLighting.NONE);
        }
        @Override public void submitGeometry(LoadedModel.Source source, PartSelection selection,
                                             PoseStack.Pose pose, int light, boolean directionalLighting, SurfaceLighting lighting) {
            PoseStack.Pose local = null;
            for (var batch : selection.batches()) {
                if (!batch.lightLayer().enabled(lights)) continue;
                if (local == null) local = pose.copy();
                addCommand(batch, local, batch.lightLayer().emissive() ? LightTexture.FULL_BRIGHT : lighting.light(light),
                        batch.lightLayer().emissive() ? 1 : lighting.emission());
            }
        }
        @Override public void submitFlare(IrLightingDefinition.Flare flare,
                                          ResourceLocation texture, PoseStack.Pose pose, int color, float strength) {
            flares.add(new IrFlareRenderer.Flare(flare, texture, pose.copy(), color, strength));
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
            int temporal = IrisVehicleReprojection.capture(train, viewPose, shadow);
            var ranges = IrisRenderStats.rangeSample(train);
            draw(snapshot, buffers, shadow, temporal, ranges);
        }
    }

    public static boolean renderPart(LoadedModel.Instance instance, String name, PoseStack pose, MultiBufferSource buffers, int light) {
        var selection = instance.model().partsByName().get(name);
        if (selection == null) return false;
        boolean shadow = IrisCompat.shadowPass();
        try (var metrics = IrisRenderStats.begin(IrisRenderStats.VEHICLE, shadow); var snapshot = new Snapshot()) {
            long start = IrisRenderStats.clock();
            snapshot.submit(instance, selection, new PoseStack(), light);
            prepare(snapshot, pose, shadow);
            IrisRenderStats.prepare(start);
            draw(snapshot, buffers, shadow, 0, null);
        }
        return true;
    }

    private static void prepare(Snapshot snapshot, PoseStack viewPose, boolean shadow) {
        snapshot.prepared.clear();
        boolean staticTransparency = !shadow && ClientConfig.USE_STATIC_TRANSLUCENT_BATCHES.getAsBoolean();
        PoseStack.Pose local = null, posed = null;
        Matrix4f view = null;
        boolean supported = false;
        for (var command : snapshot.commands) {
            if (shadow && command.batch().category() == RenderCategory.BLEND) continue;
            var material = IrisBatch.get(command.batch(), command.emission >= 1);
            // One submission shares a local pose across its batches; each view prepares it independently.
            if (local != command.pose()) {
                local = command.pose();
                var slot = snapshot.viewPose(viewPose.last(), local);
                posed = slot.pose;
                supported = IrisGeometryCache.supportsPose(posed.pose());
                view = slot.view;
            }
            var solidGpu = material.solidCount == 0 || !supported ? null : IrisGeometryCache.requestVehicleOpaque(
                    material.solidMesh, material.firstSolid, material.solidCount, material.forceOpaque);
            var lightGpu = material.batch.isLightLayer() && supported ? IrisGeometryCache.requestVehicleLight(
                    material.batch.mesh(), material.batch.firstIndex(), material.batch.indexCount()) : null;
            var clippedGpu = staticTransparency && supported && !material.batch.isLightLayer() && material.clippedCount != 0
                    ? IrisGeometryCache.requestVehicle(material.clippedMesh, material.firstClipped, material.clippedCount, false) : null;
            if (material.solidCount != 0) material.solidType();
            snapshot.addPrepared(material, posed, view, command.light(), solidGpu, clippedGpu, lightGpu);
        }
        if (!shadow) for (var flare : snapshot.flares) {
            var localPose = new PoseStack();
            localPose.last().pose().set(viewPose.last().pose()).mul(flare.pose().pose());
            IrFlareRenderer.enqueue(flare.definition(), flare.texture(), localPose.last(), RenderSystem.getModelViewMatrix(),
                    RenderSystem.getProjectionMatrix(), flare.color(), flare.strength());
        }
        if (!IrisGeometryCache.uploadVehicleOpaque()) {
            for (int i = 0; i < snapshot.prepared.size(); i++) {
                var draw = snapshot.prepared.get(i);
                if (draw.solidGpu() != null && !draw.solidGpu().ready()) {
                    draw.solidGpu = null;
                }
            }
        }
    }

    private static void draw(Snapshot snapshot, MultiBufferSource buffers, boolean shadow, int temporal,
                             IrisRenderStats.RangeSample ranges) {
        long start = IrisRenderStats.clock();
        boolean diagnostics = IrisRenderStats.collecting();
        // Complete the direct drawing range before any BufferSource getBuffer() can flush a wrapped batch.
        var opaque = snapshot.opaque;
        int groups = 0;
        try {
            for (var draw : snapshot.prepared) if (draw.solidGpu() != null) {
                var type = draw.material().solidType(temporal);
                var group = opaque.get(type);
                if (group == null) {
                    if (groups == snapshot.opaqueGroups.size()) snapshot.opaqueGroups.add(new MaterialGroup());
                    group = snapshot.opaqueGroups.get(groups++);
                    opaque.put(type, group);
                }
                group.add(draw, snapshot.opaquePose(draw.view()), diagnostics);
            }
            int poseRuns = 0, poseGroups = 0, lightGroups = 0;
            for (var group : opaque.values()) {
                poseGroups += group.sort(diagnostics);
                if (diagnostics) {
                    poseRuns += group.poseRuns;
                    lightGroups += group.count;
                }
            }
            IrisRenderStats.opaqueGrouping(start, groups, snapshot.opaqueSnapshots.size(), snapshot.opaquePoses.size(),
                    poseRuns, poseGroups, lightGroups);
            if (ranges != null) {
                long rangeStart = System.nanoTime();
                if (snapshot.rangeDiagnostics == null) snapshot.rangeDiagnostics = new RangeDiagnostics();
                snapshot.rangeDiagnostics.inspect(opaque.values(), ranges, rangeStart);
            }
            long mergeStart = IrisRenderStats.clock();
            int callsBefore = snapshot.mergeOpaque();
            IrisRenderStats.opaqueMerge(callsBefore, snapshot.opaqueCommandCount, mergeStart);
            if (!opaque.isEmpty()) try (var scope = IrisDrawScope.entity()) {
                for (int i = 0; i < snapshot.opaqueCommandCount;) {
                    var command = snapshot.opaqueCommands.get(i);
                    var draw = command.draw;
                    int end = i + 1;
                    long triangles = command.vertices / 3;
                    while (end < snapshot.opaqueCommandCount) {
                        var next = snapshot.opaqueCommands.get(end);
                        if (next.type != command.type || next.state != command.state
                                || next.draw.solidGpu().storage != draw.solidGpu().storage) break;
                        triangles += next.vertices / 3;
                        end++;
                    }
                    int count = end - i;
                    if (count == 1) {
                        scope.drawCachedRange(draw.solidGpu(), command.first, command.vertices, command.type,
                                draw.view(), RenderSystem.getProjectionMatrix(), draw.light());
                    } else {
                        snapshot.prepareRanges(i, end);
                        scope.drawCachedRanges(draw.solidGpu(), snapshot.multiRanges, count, command.type,
                                draw.view(), RenderSystem.getProjectionMatrix(), draw.light());
                    }
                    IrisRenderStats.opaqueDraw(count, triangles);
                    i = end;
                }
            }
        } finally {
            snapshot.clearOpaque(groups);
        }
        var writer = new IrisVertices();
        for (var draw : snapshot.prepared) {
            var material = draw.material();
            if (material.solidCount == 0 || draw.solidGpu() != null) continue;
            var target = buffers.getBuffer(material.solidType(temporal));
            for (int i = material.firstSolid; i < material.firstSolid + material.solidCount; i++) {
                writer.vertex(target, material.solidMesh, material.solidMesh.indices()[i], draw.pose(), draw.light(), material.forceOpaque);
            }
            IrisRenderStats.buffered(material.solidCount / 3);
        }
        IrisRenderStats.submit(start);
        if (shadow) return;
        if (ClientConfig.USE_STATIC_TRANSLUCENT_BATCHES.getAsBoolean()) {
            drawStaticTranslucent(snapshot, buffers, temporal);
            drawLightLayers(snapshot, buffers, temporal);
            return;
        }
        start = IrisRenderStats.clock();
        var sorted = snapshot.sorted;
        sorted.count = 0;
        for (int d = 0; d < snapshot.prepared.size(); d++) {
            var draw = snapshot.prepared.get(d);
            var batch = draw.material().batch;
            if (batch.category() == RenderCategory.CUTOUT || batch.isLightLayer()) continue;
            float[] centers = batch.centers();
            var m = draw.view();
            for (int i = 0; i < centers.length; i += 3) sorted.add(d, i / 3,
                    m.m02() * centers[i] + m.m12() * centers[i + 1] + m.m22() * centers[i + 2] + m.m32());
        }
        if (ClientConfig.SORT_TRANSLUCENT_TRIANGLES.getAsBoolean()) sorted.sort();
        IrisRenderStats.sort(start);
        start = IrisRenderStats.clock();
        for (int i = 0; i < sorted.count; i++) {
            int index = sorted.order[i];
            var draw = snapshot.prepared.get(sorted.command[index]);
            var material = draw.material(); var batch = material.batch;
            int triangle = sorted.triangle[index], offset = batch.firstIndex() + triangle * 3;
            var target = buffers.getBuffer(material.translucentType(triangle, temporal));
            for (int corner = 0; corner < 3; corner++) writer.vertex(target, batch.mesh(),
                    batch.mesh().indices()[offset + corner], draw.pose(), draw.light(), false);
        }
        IrisRenderStats.buffered(sorted.count);
        IrisRenderStats.submit(start);
        drawLightLayers(snapshot, buffers, temporal);
    }

    /** Shared local vertices are replayed in Iris's translucent phase; mixed alpha masks remain separate. */
    private static void drawStaticTranslucent(Snapshot snapshot, MultiBufferSource buffers, int temporal) {
        long start = IrisRenderStats.clock();
        IrisTranslucentQueue.Batch queued = null;
        var writer = new IrisVertices();
        for (var draw : snapshot.prepared) {
            var material = draw.material();
            if (material.batch.category() == RenderCategory.CUTOUT || material.batch.isLightLayer()) continue;
            if (material.solidCount != 0) {
                var type = material.translucentType(true, temporal);
                if (draw.solidGpu() != null) {
                    if (queued == null) queued = IrisTranslucentQueue.begin();
                    queued.add(draw.solidGpu(), type, draw.view(), draw.light(), false);
                } else {
                    var target = buffers.getBuffer(type);
                    for (int i = material.firstSolid; i < material.firstSolid + material.solidCount; i++) {
                        writer.vertex(target, material.solidMesh, material.solidMesh.indices()[i], draw.pose(), draw.light(), false);
                    }
                    IrisRenderStats.buffered(material.solidCount / 3);
                }
            }
            if (material.clippedCount != 0) {
                var type = material.translucentType(false, temporal);
                if (draw.clippedGpu() != null) {
                    if (queued == null) queued = IrisTranslucentQueue.begin();
                    queued.add(draw.clippedGpu(), type, draw.view(), draw.light(), false);
                } else {
                    var target = buffers.getBuffer(type);
                    for (int i = material.firstClipped; i < material.firstClipped + material.clippedCount; i++) {
                        writer.vertex(target, material.clippedMesh, material.clippedMesh.indices()[i], draw.pose(), draw.light(), false);
                    }
                    IrisRenderStats.buffered(material.clippedCount / 3);
                }
            }
        }
        IrisRenderStats.submit(start);
    }

    /** RTM light overlays use stable submission order; ordinary translucent triangles remain sorted above. */
    private static void drawLightLayers(Snapshot snapshot, MultiBufferSource buffers, int temporal) {
        long start = IrisRenderStats.clock();
        var writer = new IrisVertices();
        IrisTranslucentQueue.Batch queued = null;
        for (var draw : snapshot.prepared) {
            var material = draw.material();
            var batch = material.batch;
            if (!batch.isLightLayer()) continue;
            if (draw.lightGpu() != null) {
                if (queued == null) queued = IrisTranslucentQueue.begin();
                queued.add(draw.lightGpu(), material.translucentType(0, temporal), draw.view(), draw.light(), true);
                continue;
            }
            var target = buffers.getBuffer(material.translucentType(0, temporal));
            for (int i = batch.firstIndex(); i < batch.firstIndex() + batch.indexCount(); i++) {
                writer.lightVertex(target, batch.mesh(), batch.mesh().indices()[i], draw.pose(), draw.light());
            }
            IrisRenderStats.lightBuffered(batch.indexCount() / 3);
        }
        IrisRenderStats.submit(start);
    }

    public static void beginFrame() {
        IrisTranslucentQueue.clear();
        IrFlareRenderer.clear();
        var iterator = ENTITIES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var snapshot = entry.getValue();
            if (entry.getKey().isRemoved() || entry.getKey().level() != Minecraft.getInstance().level) {
                snapshot.close();
                iterator.remove();
            } else {
                snapshot.resetFrame();
            }
        }
    }

    public static void clear() {
        IrFlareRenderer.release();
        IrisVehicleReprojection.reset();
        ENTITIES.values().forEach(Snapshot::close);
        ENTITIES.clear();
        IrisRenderStats.reset();
        IrisGeometryCache.clear();
    }
}
