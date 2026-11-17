/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.track.mesh.NativeDiamondLayout;

/** Native diamond assembly from independent r11 part resources. */
public final class NativeDiamondModels {
    public static final String RECIPE = "native-diamond-r11-center-out-33-fixed3m-stable-support-atlas1024";
    private NativeDiamondModels() {}

    static ClientRailModelManager.Model prepare(ClientRailModelManager.Model original) {
        var parts = original.parts();
        var data = NativeDiamondRenderState.build(NativeDiamondLayout.example(), NativeTurnoutModels.sources(parts), null);
        var b = data.bounds();
        var bounds = new ModelBounds((float)b.minX(), (float)b.minY(), (float)b.minZ(),
                (float)b.maxX(), (float)b.maxY(), (float)b.maxZ());
        var batches = data.batches().stream().map(batch ->
                new ClientRailModelManager.Batch(batch.mesh(), batch.texture(), batch.doubleSided())).toList();
        return new ClientRailModelManager.Model(batches, parts, original.padding(), bounds,
                original.format(), original.period(), false, null);
    }
}
