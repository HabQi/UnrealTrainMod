/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.model.IndexedMesh;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntPredicate;

/** Immutable selection of source-local parts, shared by GPU and buffered rendering. */
public final class PartSelection implements IntPredicate {
    private final List<ModelPart> parts;
    private final List<RenderBatch> batches;
    private final BitSet indices = new BitSet();

    PartSelection(List<ModelPart> parts) {
        this(parts, parts.stream().flatMap(part -> part.batches().stream()).toList());
    }

    PartSelection(List<ModelPart> parts, List<RenderBatch> batches) {
        this.parts = List.copyOf(parts);
        this.batches = List.copyOf(batches);
        for (var part : parts) indices.set(part.index());
    }

    /** Coalesce adjacent opaque ranges within one transform, reusing the packed source's vertices/indices. */
    static PartSelection grouped(List<ModelPart> parts) {
        var opaque = new LinkedHashMap<IndexedMesh, List<RenderBatch>>();
        var batches = new ArrayList<RenderBatch>();
        for (var part : parts) {
            for (var batch : part.batches()) {
                if (batch.category() == RenderCategory.CUTOUT) {
                    opaque.computeIfAbsent(batch.mesh(), ignored -> new ArrayList<>()).add(batch);
                } else batches.add(batch);
            }
        }
        for (var ranges : opaque.values()) {
            ranges.sort(Comparator.comparingInt(RenderBatch::firstIndex));
            RenderBatch pending = null;
            for (var range : ranges) {
                if (pending != null && pending.firstIndex() + pending.indexCount() == range.firstIndex()) {
                    pending = new RenderBatch(pending.mesh(), pending.firstIndex(), pending.indexCount() + range.indexCount(),
                            pending.texture(), pending.category(), pending.mode(), pending.doubleSided());
                } else {
                    if (pending != null) batches.add(pending);
                    pending = range;
                }
            }
            if (pending != null) batches.add(pending);
        }
        return new PartSelection(parts, batches);
    }

    public List<ModelPart> parts() { return parts; }
    public List<RenderBatch> batches() { return batches; }
    @Override public boolean test(int index) { return indices.get(index); }
}

