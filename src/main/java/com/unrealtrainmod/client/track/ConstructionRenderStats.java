/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.ClientConfig;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Instrument-only CPU and draw counters; excludes preview lines and whole-frame/GPU time. */
final class ConstructionRenderStats {
    enum Path { NATIVE, IRIS, FALLBACK }
    enum Reason { GEOMETRY, NATIVE_SHADER, NATIVE_UPLOAD, IRIS_CACHE }
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/Construction");
    // passes, stations, native parts, Iris parts, fallback parts, GPU draws, GPU triangles,
    // fallback quads, native uploads, CPU total ns, CPU max ns.
    private static final long[][] COUNTS = new long[2][11];
    private static final long[][] REASONS = new long[2][Reason.values().length];
    private static long begun = System.nanoTime();

    private ConstructionRenderStats() {}

    static long begin() { return ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean() ? System.nanoTime() : 0; }

    static void part(boolean shadow, Path path, int draws, long triangles, int fallbackQuads, Reason reason) {
        if (!ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) return;
        int view = shadow ? 1 : 0;
        var counts = COUNTS[view];
        counts[2 + path.ordinal()]++;
        counts[5] += draws;
        counts[6] += triangles;
        counts[7] += fallbackQuads;
        if (reason != null) REASONS[view][reason.ordinal()]++;
    }

    static void uploaded(boolean shadow) {
        if (ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) COUNTS[shadow ? 1 : 0][8]++;
    }

    static void finish(boolean shadow, long start, int stations) {
        if (start == 0) return;
        var counts = COUNTS[shadow ? 1 : 0];
        counts[0]++;
        counts[1] += stations;
        long elapsed = System.nanoTime() - start;
        counts[9] += elapsed;
        counts[10] = Math.max(counts[10], elapsed);
    }

    static void endFrame() {
        if (!ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) { reset(); return; }
        long now = System.nanoTime();
        if (now - begun < 5_000_000_000L) return;
        for (int view = 0; view < COUNTS.length; view++) {
            var c = COUNTS[view];
            if (c[0] == 0) continue;
            var reasons = new StringBuilder();
            for (var reason : Reason.values()) {
                long count = REASONS[view][reason.ordinal()];
                if (count == 0) continue;
                if (!reasons.isEmpty()) reasons.append(',');
                reasons.append(reason.name().toLowerCase(Locale.ROOT)).append(':').append(count);
            }
            LOGGER.info("Construction models [{}]: window={}ms passes={} stations={} nativeParts={} irisParts={} fallbackParts={} gpuDraws={} gpuTriangles={} fallbackQuads={} nativeUploads={} cpuTotal={}ms cpuPerPass={}ms cpuMax={}ms reasons={}",
                    view == 0 ? "main" : "shadow", (now - begun) / 1e6, c[0], c[1], c[2], c[3], c[4],
                    c[5], c[6], c[7], c[8], c[9] / 1e6, c[9] / (1e6 * c[0]), c[10] / 1e6,
                    reasons.isEmpty() ? "none" : reasons);
        }
        reset();
    }

    static void reset() {
        for (var counts : COUNTS) Arrays.fill(counts, 0);
        for (var reasons : REASONS) Arrays.fill(reasons, 0);
        begun = System.nanoTime();
    }
}
