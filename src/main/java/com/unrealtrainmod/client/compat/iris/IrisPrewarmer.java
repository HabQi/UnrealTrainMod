/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.MaterialRenderMode;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;

/** CPU-only range registration. No model buffers, textures or PBR channels are uploaded here. */
public final class IrisPrewarmer {
    private static boolean scheduled;

    private IrisPrewarmer() {}
    public static boolean scheduled() { return scheduled; }

    public static void schedule(Collection<LoadedModel.Source> sources) {
        scheduled = true;
        var batches = Collections.newSetFromMap(new IdentityHashMap<RenderBatch, Boolean>());
        // Include whole-model and script-specific selections without duplicating batch objects.
        for (var source : sources) for (var batch : source.visibleParts().batches()) {
            batches.add(batch);
        }
        for (var source : sources) {
            for (var binding : source.irBindings().values()) {
                for (var selection : binding.staticParts().values()) for (var batch : selection.batches()) {
                    batches.add(batch);
                }
                for (var parts : binding.parts().values()) for (var part : parts) for (var batch : part.selection().batches()) {
                    batches.add(batch);
                }
            }
            for (var selection : source.bodyParts().values()) for (var batch : selection.batches()) {
                batches.add(batch);
            }
            for (var action : source.actionSelections().values()) for (var batch : action.selection().batches()) {
                batches.add(batch);
            }
            for (var part : source.parts()) for (var batch : part.batches()) {
                batches.add(batch);
            }
        }
        // Register descriptors only. A source's first accepted upload can then include all known ranges once.
        for (var batch : batches) if (batch.category() == RenderCategory.CUTOUT) {
            IrisGeometryCache.registerVehicleOpaque(batch.mesh(), batch.firstIndex(), batch.indexCount(),
                    batch.mode() == MaterialRenderMode.OPAQUE);
        }
    }

    static void clear() { scheduled = false; }
}
