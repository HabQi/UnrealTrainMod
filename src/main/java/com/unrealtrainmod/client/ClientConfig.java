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

    public static final ModConfigSpec.BooleanValue USE_VEHICLE_INSTANCING = BUILDER
            .comment("Draw instanced vehicle CUTOUT batches with one indexed instanced draw per shared mesh range.",
                    "Disabled by default for side-by-side comparison with the normal GPU draw path.",
                    "Only affects vehicle CUTOUT requests; MIXED, BLEND and CPU fallback keep their existing rules.",
                    "Requires OpenGL 3.3 and the instanced model shader; otherwise the normal path is used automatically.",
                    "Takes effect immediately without reloading models or restarting the game.")
            .translation("unrealtrainmod.configuration.useVehicleInstancing")
            .define("useVehicleInstancing", false);

    public static final ModConfigSpec.BooleanValue LOG_VEHICLE_INSTANCING_STATS = BUILDER
            .comment("Log lightweight vehicle render statistics every few seconds (world and preview separately).",
                    "Reports request counts, instanced draw calls, normal GPU draws, CPU fallback draws,",
                    "instance upload bytes, shader binds/switches, and CPU time for the complete opaque pass,",
                    "grouping, packing and uploading. Disabling skips per-draw counters and timing.",
                    "CPU timings are not GPU frame times. Enabled only for comparison; no per-instance logs.")
            .translation("unrealtrainmod.configuration.logVehicleInstancingStats")
            .define("logVehicleInstancingStats", false);

    public static final ModConfigSpec.BooleanValue USE_TRACK_RENDER_STATE_CACHE = BUILDER
            .comment("Reuse render type, shader program, samplers and direction lights across consecutive track draws.",
                    "Disabled by default to compare against the original per-mesh draw path on identical cached geometry.",
                    "Neither path rebuilds geometry, changes the build/upload budgets nor re-lays tracks.",
                    "Affects world tracks from the next frame; rail preview and thumbnails always use their own",
                    "short-lived state scope.")
            .translation("unrealtrainmod.configuration.useTrackRenderStateCache")
            .define("useTrackRenderStateCache", false);

    public static final ModConfigSpec.BooleanValue LOG_TRACK_RENDER_STATS = BUILDER
            .comment("Log lightweight track render statistics in roughly five-second windows.",
                    "Reports per-frame chunk visits, candidate/visible/empty/pending meshes, draw calls split by",
                    "native or rail-model geometry and by segment or assembly, submitted vertices and triangles,",
                    "builds, VBO uploads and bytes, render type setups, shader binds, program switches, sampled",
                    "light positions, and CPU time for the whole track stage and its sub-phases.",
                    "CPU times are submission costs, not GPU frame times. Disabling skips counters and timing.")
            .translation("unrealtrainmod.configuration.logTrackRenderStats")
            .define("logTrackRenderStats", false);

    public static final ModConfigSpec.BooleanValue SHOW_ACTIVATION_WARNING = BUILDER
            .comment("Show a flashing authentication key prompt in the top-left corner of the HUD.",
                    "Visual effect only; no activation is required.")
            .translation("unrealtrainmod.configuration.showActivationWarning")
            .define("showActivationWarning", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}
}
