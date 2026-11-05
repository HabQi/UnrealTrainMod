/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.unrealtrainmod.client.ClientConfig;
import org.slf4j.LoggerFactory;

/** Render-thread auxiliary texture accounting, separate from geometry and GPU timestamp samples. */
public final class PbrStats {
    private static long resident, peak, images, uploads, bytes, cpu, failures;
    private static long lastLog = System.nanoTime();
    private PbrStats() {}
    static void decoded() { images++; }
    static void failure() { failures++; }
    static void uploaded(long size) { uploads++; bytes += size; resident += size; peak = Math.max(peak, resident); }
    static void released(long size) { resident -= size; }
    public static void loaded(long start) { cpu += System.nanoTime() - start; }

    public static void logIfDue() {
        long now = System.nanoTime();
        if (now - lastLog < 5_000_000_000L) return;
        if (ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean() || ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) {
            LoggerFactory.getLogger("UnrealTrainMod/PBR").info(
                    "PBR textures: decoded={} uploads={} upload={}B resident={}B peak={}B loadCpu={}ms failures={} (window totals; CPU, not GPU time)",
                    images, uploads, bytes, resident, peak, cpu / 1e6, failures);
        }
        images = uploads = bytes = cpu = failures = 0;
        lastLog = now;
    }
}
