/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.renderer.RenderBatch;
import java.util.BitSet;
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

    public List<ModelPart> parts() { return parts; }
    public List<RenderBatch> batches() { return batches; }
    @Override public boolean test(int index) { return indices.get(index); }
}

