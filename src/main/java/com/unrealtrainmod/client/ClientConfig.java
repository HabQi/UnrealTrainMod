/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;
import net.minecraft.network.chat.Component;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public enum PbrQuality implements TranslatableEnum {
        OFF, LOW, FULL;
        @Override public Component getTranslatedName() {
            return Component.translatable("unrealtrainmod.configuration.pbrQuality." + name());
        }
    }

    public enum TranslucentRenderMode implements TranslatableEnum {
        PERFORMANCE, SORTED;
        @Override public Component getTranslatedName() {
            return Component.translatable("unrealtrainmod.configuration.translucentRenderMode." + name());
        }
    }

    public static final ModConfigSpec.IntValue MAX_PACKAGE_TEXTURE_SIZE = BUILDER
            .comment("Maximum longest edge of package base textures for vehicles, rails and wiring. Zero keeps original sizes.",
                    "Default 2048. Preserves aspect ratio, never enlarges images and uses nearest sampling to preserve alpha classes.",
                    "Requires F3+T or a restart; model alpha classification and atlases use the resized pixels.")
            .translation("unrealtrainmod.configuration.maxPackageTextureSize")
            .defineInRange("maxPackageTextureSize", 2048, 0, 16384);

    public static final ModConfigSpec.EnumValue<PbrQuality> PBR_QUALITY = BUILDER
            .comment("Package PBR quality: OFF uses Iris defaults; LOW limits the final auxiliary image to a 1024-pixel longest edge;",
                    "FULL keeps the auxiliary image aligned to the prepared base texture without an additional reduction.",
                    "Nearest sampling preserves packed LabPBR channels. Applies to both normal and specular channels.",
                    "Default LOW. Requires F3+T or a restart. Native rendering does not load these channels.")
            .translation("unrealtrainmod.configuration.pbrQuality")
            .defineEnum("pbrQuality", PbrQuality.LOW);

    public static final ModConfigSpec.IntValue BASE_TEXTURE_CACHE_MIB = BUILDER
            .comment("Soft GPU budget in MiB for package base textures and Iris alpha-derived textures. Allocated on first use.",
                    "CPU pixels remain available for alpha masks and re-upload. Does not include vanilla textures, GUI icons or PBR.")
            .translation("unrealtrainmod.configuration.baseTextureCacheMiB")
            .defineInRange("baseTextureCacheMiB", 512, 64, 4096);

    public static final ModConfigSpec.IntValue NATIVE_GEOMETRY_CACHE_MIB = BUILDER
            .comment("Soft GPU budget in MiB for on-demand native vehicle geometry, including previews while shaders are enabled.",
                    "Does not include Iris geometry or world track buffers. CPU model data remains loaded.")
            .translation("unrealtrainmod.configuration.nativeGeometryCacheMiB")
            .defineInRange("nativeGeometryCacheMiB", 512, 64, 4096);

    public static final ModConfigSpec.IntValue PBR_TEXTURE_CACHE_MIB = BUILDER
            .comment("Separate soft GPU budget in MiB for shared package normal and specular textures. Allocated on demand.")
            .translation("unrealtrainmod.configuration.pbrTextureCacheMiB")
            .defineInRange("pbrTextureCacheMiB", 256, 16, 4096);

    public static final ModConfigSpec.DoubleValue GPU_CACHE_RETENTION_SECONDS = BUILDER
            .comment("Minimum idle seconds before over-budget base textures, native vehicle geometry or PBR can be evicted.",
                    "Current and previous frame resources are always protected. Under-budget resources are not expired.",
                    "Budgets and retention changes apply without reloading; active/recent resources may exceed soft budgets.")
            .translation("unrealtrainmod.configuration.gpuCacheRetentionSeconds")
            .defineInRange("gpuCacheRetentionSeconds", 10.0, 0.0, 60.0);

    public static final ModConfigSpec.DoubleValue TURNOUT_ANIMATION_DURATION_SECONDS = BUILDER
            .comment("Client-only full-stroke turnout animation duration in seconds. Zero switches immediately.",
                    "Nonzero changes apply to the next throw. Routes and interlocking still switch immediately.")
            .translation("unrealtrainmod.configuration.turnoutAnimationDurationSeconds")
            .defineInRange("turnoutAnimationDurationSeconds", 1.0, 0.0, 10.0);

    public static final ModConfigSpec.EnumValue<TranslucentRenderMode> TRANSLUCENT_RENDER_MODE = BUILDER
            .comment("Vehicle transparency in native and Iris world/preview rendering. Default PERFORMANCE.",
                    "PERFORMANCE groups static batches by material, avoiding per-frame triangle sorting and dynamic uploads.",
                    "Overlapping translucent surfaces may blend incorrectly in this mode.",
                    "SORTED orders triangles from far to near, with extra CPU work and native index or Iris vertex uploads.",
                    "Changes apply on the next relevant draw without a resource reload. Light overlays remain separate.")
            .translation("unrealtrainmod.configuration.translucentRenderMode")
            .defineEnum("translucentRenderMode", TranslucentRenderMode.PERFORMANCE);

    public static final ModConfigSpec.IntValue IRIS_GEOMETRY_CACHE_MIB = BUILDER
            .comment("Soft GPU geometry cache budget in MiB for Iris vehicles, static transparency, items and track parts.",
                    "Larger budgets reduce repeated uploads when looking between detailed models; memory is allocated on demand.",
                    "Active and recently used geometry may exceed this budget. PBR textures and the separate light cache are excluded.",
                    "Menus only prepare range descriptions; geometry uploads on first use. Changes apply without reloading models.")
            .translation("unrealtrainmod.configuration.irisGeometryCacheMiB")
            .defineInRange("irisGeometryCacheMiB", 1024, 64, 4096);

    public static final ModConfigSpec.DoubleValue IRIS_GEOMETRY_CACHE_RETENTION_SECONDS = BUILDER
            .comment("Minimum idle time in seconds before Iris base geometry can be evicted when over budget.",
                    "Keeps recently viewed models resident while turning the camera. Does not expire entries while under budget.",
                    "Current and previous frame geometry is always protected, including when set to zero.",
                    "Changes apply without reloading models; the separate light cache is unchanged.")
            .translation("unrealtrainmod.configuration.irisGeometryCacheRetentionSeconds")
            .defineInRange("irisGeometryCacheRetentionSeconds", 10.0, 0.0, 60.0);

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

    public static final ModConfigSpec.BooleanValue LOG_TRACK_RENDER_STATS = BUILDER
            .comment("Log lightweight track render statistics in roughly five-second windows.",
                    "Also reports construction instruments separately: native/Iris/fallback parts, draws, triangles,",
                    "native uploads, CPU submission time and fallback reasons; excludes preview lines and text.",
                    "Reports per-frame chunk visits, candidate/visible/empty/pending meshes, draw calls split by",
                    "native or rail-model geometry and by segment or assembly, submitted vertices and triangles,",
                    "builds, VBO uploads and bytes, render type setups, shader binds, program switches, sampled",
                    "light positions, and CPU time for the whole track stage and its sub-phases, including grouping.",
                    "Also reports stored vertex records, static vertex/index bytes, separate UV2 light bytes, and chunk/material",
                    "range counts before/after merging, actual saved calls, multi-draw coverage, shared page builds and residency.",
                    "Labels the fixed bufferMode=sharedPages and reports the actual statistics window duration.",
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
