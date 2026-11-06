/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.unrealtrainmod.client.ClientConfig;
import org.slf4j.LoggerFactory;

/** Auxiliary texture accounting shared by the decoder and render thread; no GPU timestamps. */
public final class PbrStats {
    private static long resident, peak, images, uploads, bytes, decodeCpu, uploadCpu, failures;
    private static long lastLog = System.nanoTime();
    private PbrStats() {}
    static synchronized void decoded() { images++; }
    static synchronized void failure() { failures++; }
    static synchronized void uploaded(long size) { uploads++; bytes += size; resident += size; peak = Math.max(peak, resident); }
    static synchronized void released(long size) { resident -= size; }
    static synchronized void decodedCpu(long start) { decodeCpu += System.nanoTime() - start; }
    static synchronized void uploadedCpu(long start) { uploadCpu += System.nanoTime() - start; }

    public static synchronized void logIfDue() {
        long now = System.nanoTime();
        if (now - lastLog < 5_000_000_000L) return;
        if (ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean() || ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) {
            LoggerFactory.getLogger("UnrealTrainMod/PBR").info(
                    "PBR textures: decoded={} uploads={} upload={}B resident={}B peak={}B decodeCpu={}ms uploadCpu={}ms failures={} (window totals; CPU, not GPU time)",
                    images, uploads, bytes, resident, peak, decodeCpu / 1e6, uploadCpu / 1e6, failures);
        }
        images = uploads = bytes = decodeCpu = uploadCpu = failures = 0;
        lastLog = now;
    }
}
