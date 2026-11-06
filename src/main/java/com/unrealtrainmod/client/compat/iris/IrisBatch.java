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
    private static final IdentityHashMap<RenderBatch, IrisBatch> BATCHES = new IdentityHashMap<>();
    final RenderBatch batch;
    final IndexedMesh solidMesh;
    final int firstSolid, solidCount;
    final boolean forceOpaque;
    final BitSet split = new BitSet();
    private final RenderType[] solidTypes = new RenderType[2], blendTypes = new RenderType[2], clippedTypes = new RenderType[2];

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

    RenderType solidType() { return solidType(false); }

    RenderType solidType(boolean temporal) {
        int slot = temporal ? 1 : 0;
        if (solidTypes[slot] == null) {
            var texture = forceOpaque ? IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.OPAQUE)
                    : batch.category() == RenderCategory.MIXED
                    ? IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.SOLID) : batch.texture();
            solidTypes[slot] = IrisRenderTypes.get(texture, batch.doubleSided(), false, forceOpaque, temporal);
        }
        return solidTypes[slot];
    }

    void prewarmTypes() {
        solidType();
        if (batch.category() == RenderCategory.CUTOUT) return;
        int firstSolidTriangle = split.nextSetBit(0);
        if (firstSolidTriangle >= 0) translucentType(firstSolidTriangle);
        int firstOtherTriangle = split.nextClearBit(0);
        if (firstOtherTriangle < batch.indexCount() / 3) translucentType(firstOtherTriangle);
    }

    RenderType translucentType(int triangle) { return translucentType(triangle, false); }

    RenderType translucentType(int triangle, boolean temporal) {
        int slot = temporal ? 1 : 0;
        if (batch.lightLayer() != ModelLightLayer.BASE) {
            if (clippedTypes[slot] == null) clippedTypes[slot] = IrisRenderTypes.get(
                    batch.texture(), batch.doubleSided(), true, false, temporal, batch.lightLayer().emissive());
            return clippedTypes[slot];
        }
        if (split.get(triangle)) {
            if (blendTypes[slot] == null) blendTypes[slot] = IrisRenderTypes.get(
                    IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.BLEND), batch.doubleSided(), true, false, temporal);
            return blendTypes[slot];
        }
        if (clippedTypes[slot] == null) clippedTypes[slot] = IrisRenderTypes.get(
                IrisMaterials.texture(batch.texture(), IrisMaterials.Mask.CLIPPED), batch.doubleSided(), true, false, temporal);
        return clippedTypes[slot];
    }
}
