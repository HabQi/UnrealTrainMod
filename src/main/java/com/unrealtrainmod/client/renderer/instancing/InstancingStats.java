/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.instancing;

import com.unrealtrainmod.client.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-queue opaque-stage counters. The previous completed frame is logged at a low rate. */
public final class InstancingStats {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/Instancing");
    private static final long LOG_INTERVAL = 5_000_000_000L;

    private int requests, instanceable, instancedGroups, instancedRequests;
    private int gpuDraws, fallbackDraws, translucentDraws;
    private int shaderBinds, shaderSwitches;
    private boolean instancing;
    private long uploadedBytes, groupingNanos, packingNanos, uploadNanos, opaqueNanos;
    private long lastLog = System.nanoTime();

    public void resetFrame() {
        requests = instanceable = instancedGroups = instancedRequests = 0;
        gpuDraws = fallbackDraws = translucentDraws = 0;
        shaderBinds = shaderSwitches = 0;
        instancing = false;
        uploadedBytes = groupingNanos = packingNanos = uploadNanos = opaqueNanos = 0L;
    }

    public void request() { requests++; }
    public void instanceable() { instanceable++; }
    public void gpuDraw() { gpuDraws++; }
    public void fallbackDraw() { fallbackDraws++; }
    public void translucentDraw() { translucentDraws++; }

    public void instancedDraw(int instances) {
        instancedGroups++;
        instancedRequests += instances;
    }

    public void addGrouping(long nanos) { groupingNanos += nanos; }

    public void addPacking(long nanos) { packingNanos += nanos; }

    public void setInstancing(boolean enabled) { instancing = enabled; }

    public void addOpaque(long nanos) { opaqueNanos += nanos; }

    public void addShaderCounts(int binds, int switches) {
        shaderBinds += binds;
        shaderSwitches += switches;
    }

    public void addUpload(int bytes, long nanos) {
        uploadedBytes += bytes;
        uploadNanos += nanos;
    }

    /** CPU timings only; they are not GPU frame times. */
    public void logIfDue(String scene) {
        if (!ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean()) return;
        long now = System.nanoTime();
        if (now - lastLog < LOG_INTERVAL) return;
        lastLog = now;
        LOGGER.info("Vehicle instancing [{}]: instancing={} requests={} cutout={} groups={} covered={} "
                        + "gpuCalls={} cpuFallback={} translucentCalls={} shaderBinds={} shaderSwitches={} "
                        + "upload={}B group={}ms pack={}ms upload={}ms opaque={}ms",
                scene, instancing, requests, instanceable, instancedGroups, instancedRequests,
                gpuDraws, fallbackDraws, translucentDraws, shaderBinds, shaderSwitches, uploadedBytes,
                groupingNanos / 1_000_000.0, packingNanos / 1_000_000.0,
                uploadNanos / 1_000_000.0, opaqueNanos / 1_000_000.0);
    }
}
