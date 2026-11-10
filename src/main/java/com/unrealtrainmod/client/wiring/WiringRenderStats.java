/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.unrealtrainmod.client.ClientConfig;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** Wiring and blueprint submissions share the existing opt-in structure diagnostics. */
public final class WiringRenderStats {
    static long builds, hits, uploads, draws;
    private static final Map<String, Long> FALLBACKS = new HashMap<>();
    private static long last = System.nanoTime();
    private WiringRenderStats() {}
    static void fallback(String reason) { FALLBACKS.merge(reason, 1L, Long::sum); }
    static void log() {
        long now = System.nanoTime(); if (now - last < 5_000_000_000L) return; last = now;
        if (ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) LoggerFactory.getLogger("UnrealTrainMod/Wiring").info(
                "Wiring/blueprint: builds={} cacheHits={} nativeUploads={} draws={} fallbacks={}", builds, hits, uploads, draws, FALLBACKS);
        builds = hits = uploads = draws = 0; FALLBACKS.clear();
    }
}
