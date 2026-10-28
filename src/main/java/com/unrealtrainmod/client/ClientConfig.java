/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SORT_TRANSLUCENT_TRIANGLES = BUILDER
            .comment("Sort vehicle translucent triangles from far to near in world and preview rendering.",
                    "Disabling only skips sorting; depth calculation, index uploads and adjacent draw merging remain active.",
                    "Ignored while useStaticTranslucentBatches is enabled.",
                    "Overlapping translucent surfaces may blend incorrectly when disabled.")
            .translation("unrealtrainmod.configuration.sortTranslucentTriangles")
            .define("sortTranslucentTriangles", true);

    public static final ModConfigSpec.BooleanValue USE_STATIC_TRANSLUCENT_BATCHES = BUILDER
            .comment("Draw vehicle translucent batches directly from static indices in submission order.",
                    "Skips triangle collection, depth calculation, sorting and dynamic index uploads on the GPU path.",
                    "Overrides sortTranslucentTriangles; overlapping translucent surfaces may blend incorrectly.")
            .translation("unrealtrainmod.configuration.useStaticTranslucentBatches")
            .define("useStaticTranslucentBatches", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}
}
