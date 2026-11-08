/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntPredicate;
import net.minecraft.resources.ResourceLocation;

/** Immutable selection of source-local parts, shared by GPU and buffered rendering. */
public final class PartSelection implements IntPredicate {
    record Packed(PartSelection selection, long indexBytes) {}
    private record CutoutKey(IndexedMesh mesh, ResourceLocation texture, MaterialRenderMode mode, boolean doubleSided) {}
    private final List<ModelPart> parts;
    private final List<RenderBatch> batches;
    private final BitSet indices = new BitSet();

    PartSelection(List<ModelPart> parts, List<RenderBatch> batches) {
        this.parts = List.copyOf(parts);
        this.batches = List.copyOf(batches);
        for (var part : parts) indices.set(part.index());
    }

    /** Merge only consecutive cutout ranges, preserving every material and transparency boundary. */
    static PartSelection groupedInOrder(List<ModelPart> parts) {
        var batches = new ArrayList<RenderBatch>();
        RenderBatch pending = null;
        for (var part : parts) {
            for (var batch : part.batches()) {
                if (pending != null && pending.category() == RenderCategory.CUTOUT
                        && batch.category() == RenderCategory.CUTOUT && pending.mesh() == batch.mesh()
                        && pending.firstIndex() + pending.indexCount() == batch.firstIndex()
                        && pending.texture().equals(batch.texture()) && pending.mode() == batch.mode()
                        && pending.doubleSided() == batch.doubleSided()) {
                    pending = new RenderBatch(pending.mesh(), pending.firstIndex(), pending.indexCount() + batch.indexCount(),
                            pending.texture(), pending.category(), pending.mode(), pending.doubleSided());
                } else {
                    if (pending != null) batches.add(pending);
                    pending = batch;
                }
            }
        }
        if (pending != null) batches.add(pending);
        return new PartSelection(parts, batches);
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

    /** IR-only packing: keep vertices shared and preserve every non-cutout ordering boundary. */
    Packed compactCutout() {
        var packed = new ArrayList<RenderBatch>();
        var run = new LinkedHashMap<CutoutKey, List<RenderBatch>>();
        long bytes = 0;
        for (var batch : batches) {
            if (batch.category() == RenderCategory.CUTOUT) {
                var key = new CutoutKey(batch.mesh(), batch.texture(), batch.mode(), batch.doubleSided());
                run.computeIfAbsent(key, ignored -> new ArrayList<>()).add(batch);
            } else {
                bytes += packRun(run, packed);
                packed.add(batch);
            }
        }
        bytes += packRun(run, packed);
        return new Packed(new PartSelection(parts, packed), bytes);
    }

    private static long packRun(LinkedHashMap<CutoutKey, List<RenderBatch>> run, List<RenderBatch> output) {
        long bytes = 0;
        for (var ranges : run.values()) {
            var first = ranges.getFirst();
            if (ranges.size() == 1) {
                output.add(first);
                continue;
            }
            int count = 0, end = first.firstIndex();
            boolean contiguous = true;
            for (var range : ranges) {
                contiguous &= range.firstIndex() == end;
                end = range.firstIndex() + range.indexCount();
                count += range.indexCount();
            }
            var mesh = first.mesh();
            int start = first.firstIndex();
            if (!contiguous) {
                int[] indices = new int[count];
                int offset = 0;
                for (var range : ranges) {
                    System.arraycopy(mesh.indices(), range.firstIndex(), indices, offset, range.indexCount());
                    offset += range.indexCount();
                }
                mesh = new IndexedMesh(mesh.vertices(), mesh.colors(), indices);
                start = 0;
                bytes += (long) count * Integer.BYTES;
            }
            output.add(new RenderBatch(mesh, start, count, first.texture(), RenderCategory.CUTOUT,
                    first.mode(), first.doubleSided()));
        }
        run.clear();
        return bytes;
    }

    public List<ModelPart> parts() { return parts; }
    public List<RenderBatch> batches() { return batches; }
    @Override public boolean test(int index) { return indices.get(index); }
}

