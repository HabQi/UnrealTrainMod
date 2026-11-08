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
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One world view or one preview. No per-triangle objects and no vertex uploads on the normal GPU path. */
public final class ModelRenderQueue implements ModelDrawSink, AutoCloseable {
    private record Draw(RenderBatch batch, GpuModel.Part gpu, PoseStack.Pose pose, int light,
                        boolean directionalLighting) {}
    private record MaterialKey(ResourceLocation texture, boolean doubleSided, int alphaPass,
                               boolean directionalLighting) {}
    private static final class PoseKey {
        private PoseStack.Pose pose;
        private int hash, id;

        void lookup(PoseStack.Pose pose) {
            this.pose = pose;
            hash = 31 * pose.pose().hashCode() + pose.normal().hashCode();
        }

        void set(PoseKey lookup, int id) {
            pose = lookup.pose;
            hash = lookup.hash;
            this.id = id;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof PoseKey key && pose.pose().equals(key.pose.pose())
                    && pose.normal().equals(key.pose.normal());
        }
    }
    private static final class OpaqueCommand {
        private List<Draw> instances;
        private MaterialKey material;
        private long[] instanceStates = new long[0];
        private int firstIndex, rangeHash;
        private int indexCount, coveredRequests;
        private int startInstance;

        void set(List<Draw> instances) {
            this.instances = instances;
            var first = instances.getFirst();
            material = materialKey(first, false);
            firstIndex = first.gpu() == null ? 0 : first.gpu().firstIndex();
            indexCount = first.batch().indexCount();
            coveredRequests = instances.size();
        }

        Draw first() { return instances.getFirst(); }
        boolean instanced() { return instances.size() >= 2; }

        boolean prepareSignature(IdentityHashMap<PoseStack.Pose, PoseKey> poseIds) {
            int count = instances.size();
            boolean grew = instanceStates.length < count;
            if (grew) instanceStates = new long[count];
            int hash = 31 * System.identityHashCode(first().gpu().mesh()) + material.hashCode();
            for (int i = 0; i < count; i++) {
                var draw = instances.get(i);
                long value = ((long)poseIds.get(draw.pose()).id << 32) | (draw.light() & 0xffffffffL);
                instanceStates[i] = value;
                hash = 31 * hash + Long.hashCode(value);
            }
            rangeHash = hash;
            return grew;
        }

        @Override public int hashCode() { return rangeHash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof OpaqueCommand command)) return false;
            int count = instances.size();
            return first().gpu().mesh() == command.first().gpu().mesh() && material.equals(command.material)
                    && count == command.instances.size()
                    && Arrays.equals(instanceStates, 0, count, command.instanceStates, 0, count);
        }
    }
    private static final Comparator<OpaqueCommand> INDEX_ORDER = Comparator.comparingInt(command -> command.firstIndex);
    private final String scene;
    private final boolean frontLighting;
    private final boolean vehicleScene;
    private final List<Draw> draws = new ArrayList<>();
    private final List<OpaqueCommand> opaqueCommands = new ArrayList<>();
    private final List<OpaqueCommand> inputOpaqueCommands = new ArrayList<>(), commandPool = new ArrayList<>();
    private final List<List<OpaqueCommand>> rangeBucketPool = new ArrayList<>();
    private final IdentityHashMap<PoseStack.Pose, PoseKey> rangeSnapshotIds = new IdentityHashMap<>();
    private final Map<PoseKey, PoseKey> rangePoseIds = new HashMap<>();
    private final PoseKey rangePoseLookup = new PoseKey();
    private final List<PoseKey> rangePosePool = new ArrayList<>();
    private final Map<OpaqueCommand, List<OpaqueCommand>> rangeBuckets = new LinkedHashMap<>();
    private int commandCount, rangeBucketCount, commandGrowths, bucketGrowths, rangePoseCount, poseKeyGrowths;
    private PoseStack.Pose lastSnapshot;
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
    private int lights;
    private boolean started, customShader, collectStats, statsFrameStarted;
    private ByteBufferBuilder fallbackBuffer;

    public ModelRenderQueue() { this("world", false); }

    public ModelRenderQueue(String scene) { this(scene, false); }

    /** Front lighting is reserved for GUI vehicle previews; world queues retain scene lighting. */
    public ModelRenderQueue(String scene, boolean frontLighting) {
        this.scene = scene;
        this.frontLighting = frontLighting;
        this.vehicleScene = scene.equals("world") || scene.equals("preview");
    }

    /** Latch the diagnostic switch before submission so pose, script and enqueue scopes share one frame. */
    public InstancingStats statistics() {
        if (!statsFrameStarted) {
            statsFrameStarted = true;
            collectStats = vehicleScene && ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean();
            if (collectStats) stats.beginFrame();
            else stats.close();
        }
        return collectStats ? stats : null;
    }

    public void resetStatistics() { stats.close(); statsFrameStarted = collectStats = false; }

    @Override public int lights() { return lights; }
    @Override public void lights(int value) { lights = value; }

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
        submitBatches(source.gpu(), selection.batches(), pose, light, directionalLighting);
    }

    /** Static geometry submission without a vehicle definition or named-part selection. */
    public void submitBatches(GpuModel gpu, List<RenderBatch> batches, PoseStack.Pose pose, int light,
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
        PoseStack.Pose snapshot = null;
        for (var batch : batches) {
            if (!batch.lightLayer().enabled(lights)) continue;
            if (snapshot == null) snapshot = captureSnapshot(pose);
            boolean emissive = batch.lightLayer().emissive();
            draws.add(new Draw(batch, customShader && gpu != null ? gpu.part(batch) : null, snapshot,
                    emissive ? LightTexture.FULL_BRIGHT : light,
                    !emissive && directionalLighting));
        }
        if (frameStats != null) frameStats.addEnqueue(System.nanoTime() - enqueueStart);
    }

    /** Only the static shader consumes the two matrices directly; vanilla fallback also uses pose flags. */
    private PoseStack.Pose captureSnapshot(PoseStack.Pose pose) {
        boolean reused = vehicleScene && customShader && lastSnapshot != null
                && lastSnapshot.pose().equals(pose.pose()) && lastSnapshot.normal().equals(pose.normal());
        if (!reused) lastSnapshot = pose.copy();
        if (collectStats) stats.snapshotCapture(reused);
        return lastSnapshot;
    }

    public void drawOpaque() {
        RenderSystem.assertOnRenderThread();
        var frameStats = statistics();
        if (draws.isEmpty()) return;
        if (frameStats != null) frameStats.beginGpu(true);
        long opaqueStart = collectStats ? System.nanoTime() : 0L;
        boolean completed = false;
        try (var state = new ModelRenderState(frontLighting, collectStats)) {
            if (vehicleScene) drawVehicleOpaque(state);
            else drawOpaqueDirect(state);
            if (collectStats) {
                stats.addShaderCounts(state.shaderBinds(), state.shaderSwitches());
                stats.addOpaqueStateCounts(state.stateSetups(), state.textureRebinds(), state.matrixUpdates());
            }
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
        var materials = new LinkedHashMap<MaterialKey, List<Draw>>();
        for (var draw : draws) {
            if (!drawsOpaque(draw)) continue;
            if (collectStats) stats.request(draw.batch().category());
            addMaterialDraw(materials, draw, false);
        }
        drawOpaqueMaterials(state, materials);
    }

    private void drawOpaqueMaterials(ModelRenderState state, Map<MaterialKey, List<Draw>> materials) {
        for (var material : materials.values()) for (var draw : material) drawOpaqueSingle(state, draw);
    }

    private static MaterialKey materialKey(Draw draw, boolean blended) {
        var batch = draw.batch();
        return new MaterialKey(batch.texture(), batch.doubleSided(), batch.alphaPass(blended), draw.directionalLighting());
    }

    private static void addMaterialDraw(Map<MaterialKey, List<Draw>> materials, Draw draw, boolean blended) {
        materials.computeIfAbsent(materialKey(draw, blended), ignored -> new ArrayList<>()).add(draw);
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

    /** Preserve global geometry groups, then join contiguous ranges with identical ordered instance data. */
    private void drawVehicleOpaque(ModelRenderState state) {
        boolean instancing = instancingEnabled();
        long groupingStart = collectStats ? System.nanoTime() : 0L;
        var groups = new LinkedHashMap<InstanceBatchKey, List<Draw>>();
        var commands = inputOpaqueCommands;
        int fallback = 0;
        for (var draw : draws) {
            if (!drawsOpaque(draw)) continue;
            if (collectStats) stats.request(draw.batch().category());
            if (instancing && draw.gpu() != null) {
                groups.computeIfAbsent(instanceKey(draw), key -> new ArrayList<>()).add(draw);
            } else {
                commands.add(opaqueCommand(List.of(draw)));
                if (draw.gpu() == null) fallback++;
            }
        }
        for (var group : groups.values()) commands.add(opaqueCommand(group));
        if (collectStats) stats.addGrouping(System.nanoTime() - groupingStart);

        long mergeStart = collectStats ? System.nanoTime() : 0L;
        mergeOpaqueRanges(commands);
        if (collectStats) stats.addRangeMerge(commands.size() - fallback, opaqueCommands.size() - fallback,
                System.nanoTime() - mergeStart);
        var instancedMaterials = new LinkedHashMap<MaterialKey, List<OpaqueCommand>>();
        var directMaterials = new LinkedHashMap<MaterialKey, List<OpaqueCommand>>();
        for (var command : opaqueCommands) {
            var materials = command.instanced() ? instancedMaterials : directMaterials;
            materials.computeIfAbsent(command.material, ignored -> new ArrayList<>()).add(command);
        }
        if (!instancedMaterials.isEmpty()) {
            long packingStart = collectStats ? System.nanoTime() : 0L;
            instanceBuffer.begin();
            int nextInstance = 0;
            for (var material : instancedMaterials.values()) {
                for (var command : material) {
                    command.startInstance = nextInstance;
                    for (var draw : command.instances) instanceBuffer.add(draw.pose(), draw.light());
                    nextInstance += command.instances.size();
                }
            }
            long uploadStart = collectStats ? System.nanoTime() : 0L;
            if (collectStats) stats.addPacking(uploadStart - packingStart);
            instanceBuffer.upload();
            if (collectStats) stats.addUpload(instanceBuffer.uploadedBytes(), System.nanoTime() - uploadStart);
        }

        int[] attributes = instancedMaterials.isEmpty() ? null : InstancedModelShader.state().attributes();
        try {
            // Keep each shader's material buckets together, rather than switching programs per material.
            for (var material : instancedMaterials.values()) {
                for (var command : material) drawOpaqueCommand(state, command, attributes);
            }
            for (var material : directMaterials.values()) {
                for (var command : material) drawOpaqueCommand(state, command, attributes);
            }
        } finally {
            try {
                instancedMeshes.keySet().forEach(GpuModel.Mesh::releaseInstanceBuffer);
            } finally {
                instancedMeshes.clear();
                long cleanupStart = collectStats ? System.nanoTime() : 0L;
                resetOpaqueWork();
                if (collectStats) stats.addRangeCleanup(System.nanoTime() - cleanupStart);
            }
        }
    }

    private OpaqueCommand opaqueCommand(List<Draw> instances) {
        if (commandCount == commandPool.size()) {
            commandPool.add(new OpaqueCommand());
            commandGrowths++;
        }
        var command = commandPool.get(commandCount++);
        command.set(instances);
        return command;
    }

    /** Pose snapshots are immutable for this frame, so value keys can reference their matrices directly. */
    private void mergeOpaqueRanges(List<OpaqueCommand> commands) {
        long phaseStart = collectStats ? System.nanoTime() : 0L;
        int instanceValues = 0;
        for (var command : commands) {
            if (command.first().gpu() == null) continue;
            instanceValues += command.instances.size();
            for (var draw : command.instances) {
                if (rangeSnapshotIds.containsKey(draw.pose())) continue;
                var pose = draw.pose();
                rangePoseLookup.lookup(pose);
                var poseId = rangePoseIds.get(rangePoseLookup);
                if (poseId == null) {
                    if (rangePoseCount == rangePosePool.size()) {
                        rangePosePool.add(new PoseKey());
                        poseKeyGrowths++;
                    }
                    poseId = rangePosePool.get(rangePoseCount);
                    poseId.set(rangePoseLookup, rangePoseCount++);
                    rangePoseIds.put(poseId, poseId);
                }
                rangeSnapshotIds.put(pose, poseId);
            }
        }
        long poseEnd = collectStats ? System.nanoTime() : 0L;
        int signatureGrowths = 0;
        for (var command : commands) if (command.first().gpu() != null) {
            if (command.prepareSignature(rangeSnapshotIds)) signatureGrowths++;
        }
        long signatureEnd = collectStats ? System.nanoTime() : 0L;
        for (var command : commands) {
            if (command.first().gpu() == null) {
                opaqueCommands.add(command);
                continue;
            }
            // The key's mesh, material and primitive signature stay fixed; merging only changes index counts.
            var bucket = rangeBuckets.get(command);
            if (bucket == null) {
                if (rangeBucketCount == rangeBucketPool.size()) {
                    rangeBucketPool.add(new ArrayList<>());
                    bucketGrowths++;
                }
                bucket = rangeBucketPool.get(rangeBucketCount++);
                rangeBuckets.put(command, bucket);
            }
            bucket.add(command);
        }
        long bucketEnd = collectStats ? System.nanoTime() : 0L;
        for (int i = 0; i < rangeBucketCount; i++) {
            var bucket = rangeBucketPool.get(i);
            if (bucket.size() >= 2) bucket.sort(INDEX_ORDER);
        }
        long sortEnd = collectStats ? System.nanoTime() : 0L;
        for (int i = 0; i < rangeBucketCount; i++) {
            var group = rangeBucketPool.get(i);
            OpaqueCommand pending = null;
            for (var command : group) {
                if (pending != null && pending.firstIndex + pending.indexCount == command.firstIndex) {
                    pending.indexCount += command.indexCount;
                    pending.coveredRequests += command.coveredRequests;
                } else {
                    opaqueCommands.add(command);
                    pending = command;
                }
            }
        }
        if (collectStats) {
            stats.addRangePhases(poseEnd - phaseStart, signatureEnd - poseEnd, bucketEnd - signatureEnd,
                    sortEnd - bucketEnd, System.nanoTime() - sortEnd);
            stats.addRangeWork(rangeSnapshotIds.size(), rangePoseIds.size(), instanceValues, rangeBucketCount,
                    commandGrowths, signatureGrowths, bucketGrowths, poseKeyGrowths);
        }
    }

    /** Drop frame-owned references before pooled commands or their signatures are reused. */
    private void resetOpaqueWork() {
        rangeBuckets.clear();
        rangeSnapshotIds.clear();
        rangePoseIds.clear();
        rangePoseLookup.pose = null;
        for (int i = 0; i < rangePoseCount; i++) rangePosePool.get(i).pose = null;
        for (int i = 0; i < rangeBucketCount; i++) rangeBucketPool.get(i).clear();
        for (int i = 0; i < commandCount; i++) {
            var command = commandPool.get(i);
            command.instances = null;
            command.material = null;
        }
        inputOpaqueCommands.clear();
        opaqueCommands.clear();
        commandCount = rangeBucketCount = commandGrowths = bucketGrowths = 0;
        rangePoseCount = poseKeyGrowths = 0;
    }

    private void drawOpaqueCommand(ModelRenderState state, OpaqueCommand command, int[] attributes) {
        var first = command.first();
        var batch = first.batch();
        int alphaPass = batch.alphaPass(false);
        state.use(ModelRenderTypes.get(batch.texture(), batch.doubleSided(), false,
                alphaPass == StaticModelShader.FORCE_OPAQUE), false, batch.doubleSided());
        var part = first.gpu();
        if (command.instanced()) {
            state.prepareInstanced(alphaPass, first.directionalLighting(), modelView, projection);
            instancedMeshes.put(part.mesh(), Boolean.TRUE);
            part.mesh().drawInstanced(command.firstIndex, command.indexCount, instanceBuffer.handle(),
                    instanceBuffer.offsetOf(command.startInstance), command.instances.size(), attributes);
            if (collectStats) stats.instancedDraw(command.instances.size(), command.indexCount, command.coveredRequests);
        } else if (part != null) {
            state.prepareGpu(first.pose(), first.light(), alphaPass, first.directionalLighting(), modelView, projection);
            part.mesh().draw(false, command.firstIndex, command.indexCount);
            if (collectStats) stats.gpuDraw(command.indexCount);
        } else {
            state.endGpu();
            drawFallback(state, first, alphaPass, 0, command.indexCount / 3, false);
            if (collectStats) stats.fallbackDraw(command.indexCount);
        }
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

    private boolean instancingEnabled() {
        return vehicleScene && ClientConfig.USE_VEHICLE_INSTANCING.getAsBoolean() && customShader && InstancedModelShader.usable();
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
            drawLightPass();
            return;
        }
        collectTriangles();
        if (triangles != 0) {
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
        drawLightPass();
    }

    /** Static transparency favors material locality and canonical indices over surface submission order. */
    private void drawStaticTranslucent() {
        var materials = new LinkedHashMap<MaterialKey, List<Draw>>();
        for (var draw : draws) {
            if (draw.batch().category() != RenderCategory.CUTOUT && !draw.batch().isLightLayer()) {
                addMaterialDraw(materials, draw, true);
            }
        }
        if (materials.isEmpty()) return;
        try (var state = new ModelRenderState(frontLighting)) {
            for (var material : materials.values()) {
                for (int start = 0; start < material.size();) {
                    var draw = material.get(start);
                    int end = start + 1;
                    int count = draw.batch().indexCount();
                    if (draw.gpu() != null) {
                        while (end < material.size()) {
                            var next = material.get(end);
                            if (!canMerge(draw, next)
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
    }

    /** Draw RTM light overlays in submission order, avoiding the global translucent triangle sort. */
    private void drawLightPass() {
        boolean any = false;
        for (var draw : draws) if (draw.batch().isLightLayer()) { any = true; break; }
        if (!any) return;
        try (var state = new ModelRenderState(frontLighting)) {
            for (var draw : draws) {
                var batch = draw.batch();
                if (!batch.isLightLayer()) continue;
                int alphaPass = batch.alphaPass(true);
                state.use(ModelRenderTypes.get(batch.texture(), batch.doubleSided(), true, false), true,
                        batch.doubleSided());
                if (draw.gpu() != null) {
                    state.prepareGpu(draw.pose(), draw.light(), alphaPass, draw.directionalLighting(), modelView, projection);
                    draw.gpu().mesh().draw(false, draw.gpu().firstIndex(), batch.indexCount());
                } else {
                    state.endGpu();
                    drawFallback(state, draw, alphaPass, 0, batch.indexCount() / 3, false);
                }
                if (collectStats) stats.translucentDraw(batch.indexCount() / 3);
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
            if (draw.batch().category() == RenderCategory.CUTOUT || draw.batch().isLightLayer()) continue;
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
        lastSnapshot = null;
        resetOpaqueWork();
        sortedMeshes.clear();
        triangles = 0;
        started = false;
        lights = 0;
        statsFrameStarted = collectStats = false;
    }

    @Override
    public void close() {
        VehicleScriptManager.releasePreview(this);
        clear();
        stats.close();
        instanceBuffer.close();
        commandPool.clear();
        rangeBucketPool.clear();
        rangePosePool.clear();
        if (fallbackBuffer != null) {
            fallbackBuffer.close();
            fallbackBuffer = null;
        }
    }
}
