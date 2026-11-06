/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.texture.MaterialTexture;
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
            for (var selection : source.componentParts().values()) for (var batch : selection.batches()) {
                if (batches.add(batch)) PENDING.addLast(batch);
            }
            for (var selection : source.bodyParts().values()) for (var batch : selection.batches()) {
                if (batches.add(batch)) PENDING.addLast(batch);
            }
            for (var part : source.parts()) for (var batch : part.batches()) {
                if (batches.add(batch)) PENDING.addLast(batch);
            }
        }
    }

    /** At most one batch per menu frame. Large individual preparations can still exceed a frame. */
    public static void step() {
        if (PENDING.isEmpty()) return;
        var batch = PENDING.removeFirst();
        var material = IrisBatch.get(batch);
        if (material.solidCount == 0 || !IrisGeometryCache.prewarm(material.solidMesh,
                material.firstSolid, material.solidCount, material.forceOpaque)) return;
        material.prewarmTypes();
        if (!batch.texture().equals(ClientModelManager.WHITE)) {
            IrisPbrBridge.prewarm((MaterialTexture)Minecraft.getInstance().getTextureManager().getTexture(batch.texture()));
        }
    }

    static void clear() { PENDING.clear(); scheduled = false; }
}
