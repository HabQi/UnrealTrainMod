/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue TURNOUT_ANIMATION_DURATION_SECONDS = BUILDER
            .comment("Client-only full-stroke turnout animation duration in seconds. Zero switches immediately.",
                    "Nonzero changes apply to the next throw. Routes and interlocking still switch immediately.")
            .translation("unrealtrainmod.configuration.turnoutAnimationDurationSeconds")
            .defineInRange("turnoutAnimationDurationSeconds", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.BooleanValue SORT_TRANSLUCENT_TRIANGLES = BUILDER
            .comment("Sort vehicle translucent triangles from far to near in world and preview rendering.",
                    "On the native path, disabling only skips sorting; depth calculation, index uploads and adjacent draw merging remain active.",
                    "Also applies to Iris; its dynamic translucent mode retains CPU vertex submission.",
                    "Ignored while useStaticTranslucentBatches is enabled.",
                    "Overlapping translucent surfaces may blend incorrectly when disabled.")
            .translation("unrealtrainmod.configuration.sortTranslucentTriangles")
            .define("sortTranslucentTriangles", false);

    public static final ModConfigSpec.BooleanValue USE_STATIC_TRANSLUCENT_BATCHES = BUILDER
            .comment("Group vehicle translucent batches by material and draw directly from static indices.",
                    "Skips triangle collection, depth calculation, sorting and dynamic index uploads on the GPU path.",
                    "With Iris, reuses shared local GPU geometry and draws after deferred lighting instead of rewriting vertices each frame.",
                    "Overrides sortTranslucentTriangles; overlapping translucent surfaces may blend incorrectly.")
            .translation("unrealtrainmod.configuration.useStaticTranslucentBatches")
            .define("useStaticTranslucentBatches", true);

    public static final ModConfigSpec.BooleanValue USE_VEHICLE_INSTANCING = BUILDER
            .comment("Instance vehicle CUTOUT and MIXED opaque passes across all visible vehicles by shared mesh range.",
                    "Applies to native world and preview rendering; Iris uses separate material grouping and opaque range merging.",
                    "Enabled by default; disable for side-by-side comparison with the normal GPU draw path.",
                    "Opaque draws are grouped by material; translucent passes and CPU fallback are not instanced.",
                    "Requires OpenGL 3.3 and the instanced model shader; otherwise the normal path is used automatically.",
                    "Takes effect immediately without reloading models or restarting the game.")
            .translation("unrealtrainmod.configuration.useVehicleInstancing")
            .define("useVehicleInstancing", true);

    public static final ModConfigSpec.BooleanValue LOG_VEHICLE_INSTANCING_STATS = BUILDER
            .comment("Log five-second vehicle render windows for world and preview queues separately.",
                    "Reports per-frame draw and instance counts, triangles multiplied by instance count, model counts,",
                    "instance bytes, and CPU entity/pose/submission/script/enqueue/opaque/translucent timings.",
                    "CPU scopes overlap: entity includes submission; submission includes scripts and enqueueing.",
                    "Separately samples opaque and translucent GPU timeline spans every 16 pass calls without waiting.",
                    "GPU spans include instance uploads and possible idle gaps; they are not full-frame GPU times.",
                    "Reports native range-merge phase timings, workload and workspace growth without rescanning geometry.",
                    "Iris reports separate main/shadow material and shader state counts, CPU scope phases and sampled GPU direct-range times.",
                    "Iris state counts include actual buffer binds, requested/saved binds and window totals for preparation pool growth.",
                    "Iris opaque diagnostics compare pose runs before grouping with exact per-material pose/light groups.",
                    "Iris also reports actual opaque range calls before/after merging, merge CPU cost, and shared buffer uploads/residency.",
                    "Opaque range counts remain separate from actual API calls; multi-draw reports calls, covered ranges and saved calls.",
                    "Once per warmed-up main/shadow window, samples one train's buffer/source/range layout without scanning vertices.",
                    "Iris GPU samples cover one direct vehicle range, not an entire view or the shader pack's full frame.",
                    "IR loading reports static/animated batch layout and transform-group composition while enabled.",
                    "Disabling skips counters/timing and releases queries at the next vehicle queue frame.")
            .translation("unrealtrainmod.configuration.logVehicleInstancingStats")
            .define("logVehicleInstancingStats", false);

    public static final ModConfigSpec.BooleanValue LOG_ITEM_RENDER_STATS = BUILDER
            .comment("Log five-second static item rendering windows for GUI, hand and world contexts.",
                    "Reports screenshot hits/generation, native/Iris GPU draws, triangles, standard fallbacks and CPU submission time.",
                    "Also splits paths and fallback reasons by display context and main/shadow view.",
                    "Screenshot generation includes readback/upload; these counters are not GPU timings or full-frame measurements.")
            .translation("unrealtrainmod.configuration.logItemRenderStats")
            .define("logItemRenderStats", false);

    public static final ModConfigSpec.BooleanValue USE_TRACK_RENDER_STATE_CACHE = BUILDER
            .comment("Reuse render type, shader program, samplers and direction lights across consecutive native track draws.",
                    "Disabled by default to compare against the original per-mesh draw path on identical cached geometry.",
                    "All paths reuse cached geometry and keep the same build/upload budgets without re-laying tracks.",
                    "When groupTrackDrawsByMaterial is enabled, visible batches are grouped across meshes.",
                    "Only controls the native backend; Iris uses its own draw state scope.",
                    "Affects world tracks from the next frame; rail preview and thumbnails always use their own",
                    "short-lived state scope.")
            .translation("unrealtrainmod.configuration.useTrackRenderStateCache")
            .define("useTrackRenderStateCache", false);

    public static final ModConfigSpec.BooleanValue GROUP_TRACK_DRAWS_BY_MATERIAL = BUILDER
            .comment("Group visible world track batches by render type across meshes before drawing.",
                    "Enabled by default; native rendering requires useTrackRenderStateCache, while Iris groups independently.",
                    "Fixed meshes reduce state changes; rigid turnout parts also group shared-mesh instances.",
                    "Changes submission order between materials; coplanar overlays may be covered differently.",
                    "Takes effect on the next frame without rebuilding meshes; previews and thumbnails are unchanged.")
            .translation("unrealtrainmod.configuration.groupTrackDrawsByMaterial")
            .define("groupTrackDrawsByMaterial", true);

    public static final ModConfigSpec.BooleanValue LOG_TRACK_RENDER_STATS = BUILDER
            .comment("Log lightweight track render statistics in roughly five-second windows.",
                    "Reports per-frame chunk visits, candidate/visible/empty/pending meshes, draw calls split by",
                    "native or rail-model geometry and by segment or assembly, submitted vertices and triangles,",
                    "builds, VBO uploads and bytes, render type setups, shader binds, program switches, sampled",
                    "light positions, and CPU time for the whole track stage and its sub-phases, including grouping.",
                    "Also reports stored vertex records, explicit index upload bytes, and ordinary rail chunk/material",
                    "batch duplication in the materialGroups path. Duplication is potential merge space, not saved draws.",
                    "Also samples the track GPU timeline span every 16 frames using nonblocking timestamps,",
                    "excluding builds, static uploads and empty frames; motion samples include streaming uploads.",
                    "Separates stationary and animated GPU samples, and reports turnout/JS/part/streaming metrics.",
                    "CPU times are submission costs; GPU spans are not full-frame times or pure shader activity.",
                    "Disabling skips counters and timing and releases the GPU queries on the next track pass.")
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
