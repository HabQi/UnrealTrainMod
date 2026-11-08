/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.texture.MaterialTexture;
import com.unrealtrainmod.model.MaterialRenderMode;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import net.minecraft.client.Minecraft;

/** Menu-only preparation. Keep half the geometry cache available for the actual world's working set. */
public final class IrisPrewarmer {
    private static final ArrayDeque<RenderBatch> PENDING = new ArrayDeque<>();
    private static boolean scheduled;

    private IrisPrewarmer() {}
    public static boolean scheduled() { return scheduled; }

    public static void schedule(Collection<LoadedModel.Source> sources) {
        scheduled = true;
        var batches = Collections.newSetFromMap(new IdentityHashMap<RenderBatch, Boolean>());
        // Common whole-model selections first; script-specific ranges follow without duplicating batch objects.
        for (var source : sources) for (var batch : source.visibleParts().batches()) {
            if (batches.add(batch)) PENDING.addLast(batch);
        }
        for (var source : sources) {
            for (var binding : source.irBindings().values()) {
                for (var selection : binding.staticParts().values()) for (var batch : selection.batches()) {
                    if (batches.add(batch)) PENDING.addLast(batch);
                }
                for (var parts : binding.parts().values()) for (var part : parts) for (var batch : part.selection().batches()) {
                    if (batches.add(batch)) PENDING.addLast(batch);
                }
            }
            for (var selection : source.bodyParts().values()) for (var batch : selection.batches()) {
                if (batches.add(batch)) PENDING.addLast(batch);
            }
            for (var action : source.actionSelections().values()) for (var batch : action.selection().batches()) {
                if (batches.add(batch)) PENDING.addLast(batch);
            }
            for (var part : source.parts()) for (var batch : part.batches()) {
                if (batches.add(batch)) PENDING.addLast(batch);
            }
        }
        // Register descriptors only. A source's first accepted upload can then include all known ranges once.
        for (var batch : batches) if (batch.category() == RenderCategory.CUTOUT) {
            IrisGeometryCache.registerVehicleOpaque(batch.mesh(), batch.firstIndex(), batch.indexCount(),
                    batch.mode() == MaterialRenderMode.OPAQUE);
        }
    }

    /** At most one batch per menu frame. Large individual preparations can still exceed a frame. */
    public static void step() {
        if (PENDING.isEmpty()) return;
        var batch = PENDING.removeFirst();
        var material = IrisBatch.get(batch);
        boolean prepared = material.solidCount != 0 && IrisGeometryCache.prewarmVehicleOpaque(material.solidMesh,
                material.firstSolid, material.solidCount, material.forceOpaque);
        if (ClientConfig.USE_STATIC_TRANSLUCENT_BATCHES.getAsBoolean() && !batch.isLightLayer() && material.clippedCount != 0) {
            prepared |= IrisGeometryCache.prewarm(material.clippedMesh, material.firstClipped, material.clippedCount, false);
        }
        if (!prepared) return;
        material.prewarmTypes();
        if (!batch.texture().equals(ClientModelManager.WHITE)) {
            IrisPbrBridge.prewarm((MaterialTexture)Minecraft.getInstance().getTextureManager().getTexture(batch.texture()));
        }
    }

    static void clear() { PENDING.clear(); scheduled = false; }
}
