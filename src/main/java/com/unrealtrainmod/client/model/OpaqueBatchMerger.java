/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Packs static cutout geometry once; part selections and the visible union only differ in index ranges. */
final class OpaqueBatchMerger {
    record Result(List<ModelPart> parts, List<RenderBatch> visibleBatches) {}
    private record Key(ResourceLocation texture, MaterialRenderMode mode, boolean doubleSided) {
        static Key of(RenderBatch batch, Map<ResourceLocation, TextureAtlas.Region> regions) {
            var region = regions.get(batch.texture());
            return new Key(region == null ? batch.texture() : region.texture(), batch.mode(), batch.doubleSided());
        }
    }
    private record Range(int part, int firstIndex, int indexCount) {}
    private record VertexSource(float[] vertices, ResourceLocation texture) {}

    private OpaqueBatchMerger() {}

    static int visibleBatchCount(List<ModelPart> parts) {
        var states = new HashSet<Key>();
        for (var part : parts) if (part.visible()) {
            for (var batch : part.batches()) if (batch.category() == RenderCategory.CUTOUT) {
                states.add(Key.of(batch, Map.of()));
            }
        }
        return states.size();
    }

    static Result merge(List<ModelPart> parts, Map<ResourceLocation, TextureAtlas.Region> regions) {
        var groups = new LinkedHashMap<Key, Group>();
        // Visible ranges form a prefix in every group, so the default selection needs one draw per state.
        for (int pass = 0; pass < 2; pass++) {
            for (var part : parts) {
                if (part.visible() != (pass == 0)) continue;
                var byState = new LinkedHashMap<Key, List<RenderBatch>>();
                for (var batch : part.batches()) {
                    if (batch.category() == RenderCategory.CUTOUT) {
                        byState.computeIfAbsent(Key.of(batch, regions), ignored -> new ArrayList<>()).add(batch);
                    }
                }
                byState.forEach((key, batches) -> groups.computeIfAbsent(key, Group::new).append(part, batches, regions));
            }
        }

        var replacements = new ArrayList<Map<Key, RenderBatch>>();
        for (int i = 0; i < parts.size(); i++) replacements.add(new HashMap<>());
        var visibleByState = new HashMap<Key, RenderBatch>();
        for (var group : groups.values()) {
            var mesh = new IndexedMesh(group.vertices.toFloatArray(), group.colors.toIntArray(), group.indices.toIntArray());
            for (var range : group.ranges) {
                replacements.get(range.part()).put(group.key, group.batch(mesh, range.firstIndex(), range.indexCount()));
            }
            if (group.visibleIndexCount > 0) {
                visibleByState.put(group.key, group.batch(mesh, 0, group.visibleIndexCount));
            }
        }

        var mergedParts = new ArrayList<ModelPart>();
        var visibleBatches = new ArrayList<RenderBatch>();
        for (var part : parts) {
            var batches = new ArrayList<RenderBatch>();
            for (var batch : part.batches()) {
                if (batch.category() == RenderCategory.CUTOUT) {
                    var key = Key.of(batch, regions);
                    var replacement = replacements.get(part.index()).remove(key);
                    if (replacement != null) batches.add(replacement);
                    if (part.visible()) {
                        var combined = visibleByState.remove(key);
                        if (combined != null) visibleBatches.add(combined);
                    }
                } else {
                    // Keep transparent batch objects and their relative order, including MIXED's opaque pass.
                    batches.add(batch);
                    if (part.visible()) visibleBatches.add(batch);
                }
            }
            mergedParts.add(new ModelPart(part.index(), part.name(), part.visible(), batches));
        }
        return new Result(List.copyOf(mergedParts), List.copyOf(visibleBatches));
    }

    private static final class Group {
        private final Key key;
        private final FloatArrayList vertices = new FloatArrayList();
        private final IntArrayList colors = new IntArrayList(), indices = new IntArrayList();
        // Array identity retains the original vertex sharing; texture selects the UV transform.
        private final Map<VertexSource, int[]> remaps = new HashMap<>();
        private final List<Range> ranges = new ArrayList<>();
        private int visibleIndexCount;

        private Group(Key key) { this.key = key; }

        private void append(ModelPart part, List<RenderBatch> batches, Map<ResourceLocation, TextureAtlas.Region> regions) {
            int first = indices.size();
            for (var batch : batches) {
                var mesh = batch.mesh();
                var region = regions.get(batch.texture());
                int[] remap = remaps.computeIfAbsent(new VertexSource(mesh.vertices(), batch.texture()), ignored -> {
                    int[] mapping = new int[mesh.colors().length];
                    Arrays.fill(mapping, -1);
                    return mapping;
                });
                for (int i = batch.firstIndex(); i < batch.firstIndex() + batch.indexCount(); i++) {
                    int source = mesh.indices()[i];
                    int target = remap[source];
                    if (target < 0) {
                        target = colors.size();
                        remap[source] = target;
                        int offset = source * 8;
                        float[] data = mesh.vertices();
                        vertices.add(data[offset]); vertices.add(data[offset + 1]); vertices.add(data[offset + 2]);
                        float u = data[offset + 3], v = data[offset + 4];
                        vertices.add(region == null ? u : region.u(u));
                        vertices.add(region == null ? v : region.v(v));
                        for (int axis = 5; axis < 8; axis++) vertices.add(data[offset + axis]);
                        colors.add(mesh.colors()[source]);
                    }
                    indices.add(target);
                }
            }
            ranges.add(new Range(part.index(), first, indices.size() - first));
            if (part.visible()) visibleIndexCount = indices.size();
        }

        private RenderBatch batch(IndexedMesh mesh, int first, int count) {
            return new RenderBatch(mesh, first, count, key.texture(), RenderCategory.CUTOUT, key.mode(), key.doubleSided());
        }
    }
}
