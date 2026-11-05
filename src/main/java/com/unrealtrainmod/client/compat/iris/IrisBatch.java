/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.RenderType;

/** Resource-local alpha decisions, shared by entities, cameras and frames. */
final class IrisBatch {
    private static final IdentityHashMap<RenderBatch, IrisBatch> BATCHES = new IdentityHashMap<>();
    final RenderBatch batch;
    final IndexedMesh solidMesh;
    final int firstSolid, solidCount;
    final boolean forceOpaque;
    final BitSet split = new BitSet();
    private RenderType solidType, blendType, clippedType;

    static IrisBatch get(RenderBatch batch) { return BATCHES.computeIfAbsent(batch, IrisBatch::new); }
    static void clear() { BATCHES.clear(); }

    private IrisBatch(RenderBatch batch) {
        this.batch = batch;
        forceOpaque = batch.mode() == MaterialRenderMode.OPAQUE;
        if (batch.category() == RenderCategory.CUTOUT) {
            solidMesh = batch.mesh();
            firstSolid = batch.firstIndex();
            solidCount = batch.indexCount();
        } else if (batch.category() == RenderCategory.MIXED) {
            var selected = new IntArrayList();
            int[] indices = batch.mesh().indices(), colors = batch.mesh().colors();
            for (int relative = 0; relative < batch.indexCount(); relative += 3) {
                int offset = batch.firstIndex() + relative;
                if ((colors[indices[offset]] >>> 24) == 255 && (colors[indices[offset + 1]] >>> 24) == 255
                        && (colors[indices[offset + 2]] >>> 24) == 255) {
                    split.set(relative / 3);
                    selected.add(indices[offset]); selected.add(indices[offset + 1]); selected.add(indices[offset + 2]);
                }
            }
            solidMesh = new IndexedMesh(batch.mesh().vertices(), colors, selected.toIntArray());
            firstSolid = 0;
            solidCount = selected.size();
        } else {
            solidMesh = batch.mesh();
            firstSolid = solidCount = 0;
        }
    }

    RenderType solidType() {
        if (solidType == null) {
            var texture = forceOpaque ? IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.OPAQUE)
                    : batch.category() == RenderCategory.MIXED
                    ? IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.SOLID) : batch.texture();
            solidType = IrisRenderTypes.get(texture, batch.doubleSided(), false, forceOpaque);
        }
        return solidType;
    }

    RenderType translucentType(int triangle) {
        if (split.get(triangle)) {
            if (blendType == null) blendType = IrisRenderTypes.get(
                    IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.BLEND), batch.doubleSided(), true, false);
            return blendType;
        }
        if (clippedType == null) clippedType = IrisRenderTypes.get(
                IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.CLIPPED), batch.doubleSided(), true, false);
        return clippedType;
    }
}
