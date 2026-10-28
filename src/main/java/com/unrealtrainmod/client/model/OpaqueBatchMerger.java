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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Packs static cutout geometry once; part selections and the visible union only differ in index ranges. */
final class OpaqueBatchMerger {
    record Result(List<ModelPart> parts, List<RenderBatch> visibleBatches) {}
    private record Key(ResourceLocation texture, MaterialRenderMode mode, boolean doubleSided) {
        static Key of(RenderBatch batch) { return new Key(batch.texture(), batch.mode(), batch.doubleSided()); }
    }
    private record Range(int part, int firstIndex, int indexCount) {}

    private OpaqueBatchMerger() {}

    static Result merge(List<ModelPart> parts) {
        var groups = new LinkedHashMap<Key, Group>();
        // Visible ranges form a prefix in every group, so the default selection needs one draw per state.
        for (int pass = 0; pass < 2; pass++) {
            for (var part : parts) {
                if (part.visible() != (pass == 0)) continue;
                var byState = new LinkedHashMap<Key, List<RenderBatch>>();
                for (var batch : part.batches()) {
                    if (batch.category() == RenderCategory.CUTOUT) {
                        byState.computeIfAbsent(Key.of(batch), ignored -> new ArrayList<>()).add(batch);
                    }
                }
                byState.forEach((key, batches) -> groups.computeIfAbsent(key, Group::new).append(part, batches));
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
                    var key = Key.of(batch);
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
        private final IdentityHashMap<float[], int[]> remaps = new IdentityHashMap<>();
        private final List<Range> ranges = new ArrayList<>();
        private int visibleIndexCount;

        private Group(Key key) { this.key = key; }

        private void append(ModelPart part, List<RenderBatch> batches) {
            int first = indices.size();
            for (var batch : batches) {
                var mesh = batch.mesh();
                int[] remap = remaps.computeIfAbsent(mesh.vertices(), ignored -> {
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
                        for (int axis = 0; axis < 8; axis++) vertices.add(mesh.vertices()[source * 8 + axis]);
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
