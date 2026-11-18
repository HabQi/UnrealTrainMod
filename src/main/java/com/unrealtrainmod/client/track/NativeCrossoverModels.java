/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.track.mesh.NativeCrossoverLayout;
import com.unrealtrainmod.track.mesh.RailMeshAccumulator;

/** Icons assemble the shared whole-model geometry without preparing world chunk partitions. */
public final class NativeCrossoverModels {
    public static final String RECIPE = "native-scissors-17-fixed3m-stable-support-atlas1024";
    public static final String SINGLE_RECIPE = "native-single-crossover-1-shared-r11";
    private NativeCrossoverModels() {}
    static ClientRailModelManager.Model prepare(ClientRailModelManager.Model original) {
        return prepare(original, NativeCrossoverLayout.Kind.SCISSORS);
    }
    static ClientRailModelManager.Model prepare(ClientRailModelManager.Model original, NativeCrossoverLayout.Kind kind) {
        var parts = original.parts();
        var layout = NativeCrossoverLayout.example(kind);
        var output = new RailMeshAccumulator(layout.origin());
        try (var fixed = new NativeCrossoverMeshes(layout, original)) {
            NativeCrossoverMeshes.appendData(output, layout.origin(), fixed.build(null));
            for (var core : fixed.switches().values()) {
                var prepared = NativeTurnoutMeshes.prepare(core, fixed.sources());
                try { NativeCrossoverMeshes.appendData(output, layout.origin(), NativeTurnoutMeshes.build(core, prepared, null, true, 0)); }
                finally { NativeTurnoutMeshes.release(prepared); }
            }
        }
        var data = output.finish(); var bounds = data.bounds();
        var batches = data.batches().stream().map(batch -> new ClientRailModelManager.Batch(batch.mesh(), batch.texture(), batch.doubleSided())).toList();
        return new ClientRailModelManager.Model(batches, parts, original.padding(),
                new ModelBounds((float)bounds.minX(), (float)bounds.minY(), (float)bounds.minZ(),
                        (float)bounds.maxX(), (float)bounds.maxY(), (float)bounds.maxZ()),
                original.format(), original.period(), false, null);
    }
}
