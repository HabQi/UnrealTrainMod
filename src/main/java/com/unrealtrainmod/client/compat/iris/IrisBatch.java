/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import com.unrealtrainmod.model.ModelLightLayer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.RenderType;

/** Resource-local alpha decisions, shared by entities, cameras and frames. */
final class IrisBatch {
    private static final IdentityHashMap<RenderBatch, IrisBatch[]> BATCHES = new IdentityHashMap<>();
    final RenderBatch batch;
    private final boolean emissive;
    final IndexedMesh solidMesh;
    final int firstSolid, solidCount;
    final IndexedMesh clippedMesh;
    final int firstClipped, clippedCount;
    final boolean forceOpaque;
    final BitSet split = new BitSet();
    private final RenderType[] solidTypes = new RenderType[3], blendTypes = new RenderType[3], clippedTypes = new RenderType[3];

    static IrisBatch get(RenderBatch batch) { return get(batch, false); }
    static IrisBatch get(RenderBatch batch, boolean emissive) {
        var variants = BATCHES.computeIfAbsent(batch, ignored -> new IrisBatch[2]);
        int index = emissive ? 1 : 0;
        if (variants[index] == null) variants[index] = new IrisBatch(batch, emissive);
        return variants[index];
    }
    static void clear() { BATCHES.clear(); }

    private IrisBatch(RenderBatch batch, boolean emissive) {
        this.batch = batch;
        this.emissive = emissive;
        forceOpaque = batch.mode() == MaterialRenderMode.OPAQUE;
        if (batch.category() == RenderCategory.CUTOUT) {
            solidMesh = batch.mesh();
            firstSolid = batch.firstIndex();
            solidCount = batch.indexCount();
            clippedMesh = batch.mesh();
            firstClipped = clippedCount = 0;
        } else if (batch.category() == RenderCategory.MIXED) {
            var selected = new IntArrayList();
            var clipped = new IntArrayList();
            int[] indices = batch.mesh().indices(), colors = batch.mesh().colors();
            for (int relative = 0; relative < batch.indexCount(); relative += 3) {
                int offset = batch.firstIndex() + relative;
                if ((colors[indices[offset]] >>> 24) == 255 && (colors[indices[offset + 1]] >>> 24) == 255
                        && (colors[indices[offset + 2]] >>> 24) == 255) {
                    split.set(relative / 3);
                    selected.add(indices[offset]); selected.add(indices[offset + 1]); selected.add(indices[offset + 2]);
                } else {
                    clipped.add(indices[offset]); clipped.add(indices[offset + 1]); clipped.add(indices[offset + 2]);
                }
            }
            solidMesh = new IndexedMesh(batch.mesh().vertices(), colors, selected.toIntArray());
            firstSolid = 0;
            solidCount = selected.size();
            clippedMesh = new IndexedMesh(batch.mesh().vertices(), colors, clipped.toIntArray());
            firstClipped = 0;
            clippedCount = clipped.size();
        } else {
            solidMesh = batch.mesh();
            firstSolid = solidCount = 0;
            clippedMesh = batch.mesh();
            firstClipped = batch.firstIndex();
            clippedCount = batch.indexCount();
        }
    }

    RenderType solidType() { return solidType(0); }

    RenderType solidType(int temporal) {
        if (solidTypes[temporal] == null) {
            var texture = forceOpaque ? IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.OPAQUE)
                    : batch.category() == RenderCategory.MIXED
                    ? IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.SOLID) : batch.texture();
            solidTypes[temporal] = IrisRenderTypes.get(texture, batch.doubleSided(), false, forceOpaque, temporal, emissive);
        }
        return solidTypes[temporal];
    }

    RenderType translucentType(int triangle) { return translucentType(triangle, 0); }

    RenderType translucentType(int triangle, int temporal) {
        return translucentType(split.get(triangle), temporal);
    }

    RenderType translucentType(boolean blend, int temporal) {
        if (batch.lightLayer() != ModelLightLayer.BASE) {
            if (clippedTypes[temporal] == null) clippedTypes[temporal] = IrisRenderTypes.get(
                    batch.texture(), batch.doubleSided(), true, false, temporal, batch.lightLayer().emissive());
            return clippedTypes[temporal];
        }
        if (blend) {
            if (blendTypes[temporal] == null) blendTypes[temporal] = IrisRenderTypes.get(
                    IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.BLEND), batch.doubleSided(), true, false, temporal, emissive);
            return blendTypes[temporal];
        }
        if (clippedTypes[temporal] == null) clippedTypes[temporal] = IrisRenderTypes.get(
                IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.CLIPPED), batch.doubleSided(), true, false, temporal, emissive);
        return clippedTypes[temporal];
    }
}
