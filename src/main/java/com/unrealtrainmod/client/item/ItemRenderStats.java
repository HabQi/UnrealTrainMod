/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.item;

import com.unrealtrainmod.client.ClientConfig;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.world.item.ItemDisplayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional CPU/call counters; these are neither GPU timings nor whole-frame measurements. */
final class ItemRenderStats {
    enum Path { ICON, NATIVE, IRIS, FALLBACK }
    enum Reason { NONE, GEOMETRY, FOIL, OVERLAY, ICON_PENDING, ICON_FAILED, NATIVE_BUFFER, NATIVE_GPU,
                  IRIS_BUFFER, IRIS_POSE, IRIS_CACHE }
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/Item");
    private static final String[] SCENES = {"gui", "hand", "world"};
    private static final int PATH_COUNT = Path.values().length;
    // calls, icon hits, native calls, Iris calls, fallback calls, GPU draws, triangles, CPU ns
    private static final long[][] COUNTS = new long[3][8];
    // Per display context and main/shadow view: path counts followed by fallback reasons.
    private static final long[][][] DETAILS = new long[ItemDisplayContext.values().length][2]
            [PATH_COUNT + Reason.values().length];
    private static long begun = System.nanoTime(), generated, generationNanos;

    private ItemRenderStats() {}

    static long begin() { return ClientConfig.LOG_ITEM_RENDER_STATS.getAsBoolean() ? System.nanoTime() : 0; }

    static void finish(ItemDisplayContext context, boolean shadow, long start, Path path, Reason reason,
                       int draws, long triangles) {
        if (start == 0) return;
        boolean hand = context.firstPerson() || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        var counts = COUNTS[context == ItemDisplayContext.GUI ? 0 : hand ? 1 : 2];
        counts[0]++;
        counts[1 + path.ordinal()]++;
        counts[5] += draws;
        counts[6] += triangles;
        counts[7] += System.nanoTime() - start;
        var details = DETAILS[context.ordinal()][shadow ? 1 : 0];
        details[path.ordinal()]++;
        if (path == Path.FALLBACK) details[PATH_COUNT + reason.ordinal()]++;
    }

    static void generated(long start) {
        if (start == 0) return;
        generated++;
        generationNanos += System.nanoTime() - start;
    }

    static void endFrame() {
        if (!ClientConfig.LOG_ITEM_RENDER_STATS.getAsBoolean()) { reset(); return; }
        long now = System.nanoTime();
        if (now - begun < 5_000_000_000L) return;
        for (int scene = 0; scene < COUNTS.length; scene++) {
            var c = COUNTS[scene];
            if (c[0] == 0) continue;
            LOGGER.info("Item render [{}]: calls={} iconHits={} native={} iris={} fallback={} gpuDraws={} gpuTriangles={} cpuTotal={}ms cpuPerCall={}ms",
                    SCENES[scene], c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7] / 1e6, c[7] / (1e6 * c[0]));
        }
        for (var context : ItemDisplayContext.values()) for (int view = 0; view < 2; view++) {
            var d = DETAILS[context.ordinal()][view];
            long calls = d[0] + d[1] + d[2] + d[3];
            if (calls == 0) continue;
            var reasons = new StringBuilder();
            for (var reason : Reason.values()) {
                long count = d[PATH_COUNT + reason.ordinal()];
                if (count == 0) continue;
                if (!reasons.isEmpty()) reasons.append(',');
                reasons.append(reason.name().toLowerCase(Locale.ROOT)).append(':').append(count);
            }
            LOGGER.info("Item context [{}/{}]: calls={} iconHits={} native={} iris={} fallback={} reasons={}",
                    context.getSerializedName(), view == 0 ? "main" : "shadow", calls, d[0], d[1], d[2], d[3],
                    reasons.isEmpty() ? "none" : reasons);
        }
        if (generated != 0) LOGGER.info("Item icons: generated={} size=256x256 captureCpu={}ms (includes readback/upload)",
                generated, generationNanos / 1e6);
        reset();
    }

    static void reset() {
        for (var counts : COUNTS) Arrays.fill(counts, 0);
        for (var context : DETAILS) for (var view : context) Arrays.fill(view, 0);
        generated = generationNanos = 0;
        begun = System.nanoTime();
    }
}
