/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.texture;

import com.unrealtrainmod.client.ClientConfig;
import java.util.LinkedHashSet;
import org.slf4j.LoggerFactory;

/** Render-thread residency for CPU pixels that can be restored on demand. */
public final class TexturePixelCache {
    private static final LinkedHashSet<MaterialTexture> RESIDENT = new LinkedHashSet<>();
    private static long bytes, peak, evictions, evictedBytes, restores, restoredBytes, failures;
    private static long lastLog = System.nanoTime();

    private TexturePixelCache() {}

    static void loaded(MaterialTexture texture, long size) {
        RESIDENT.add(texture);
        bytes += size;
        peak = Math.max(peak, bytes);
    }

    static void used(MaterialTexture texture) {
        if (RESIDENT.remove(texture)) RESIDENT.add(texture);
    }

    static void released(MaterialTexture texture) {
        if (RESIDENT.remove(texture)) bytes -= texture.pixelBytes();
    }

    static void restored(long size) { restores++; restoredBytes += size; }
    static void failed() { failures++; }

    private static long limit() { return (long)ClientConfig.VEHICLE_TEXTURE_PIXELS_MIB.get() * 1024 * 1024; }

    public static void trim() {
        long limit = limit();
        if (bytes <= limit) return;
        var entries = RESIDENT.iterator();
        while (bytes > limit && entries.hasNext()) {
            var texture = entries.next();
            if (!texture.pixelsIdle()) continue;
            long size = texture.pixelBytes();
            entries.remove();
            bytes -= size;
            evictions++;
            evictedBytes += size;
            texture.discardPixels();
        }
    }

    public static void endFrame() {
        trim();
        long now = System.nanoTime();
        if (now - lastLog < 5_000_000_000L) return;
        if (ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean() || ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean()) {
            LoggerFactory.getLogger("UnrealTrainMod/TexturePixels").info(
                    "Vehicle texture pixels: resident={}B/{}B entries={} windowPeak={}B restored={} restoredBytes={}B failures={} evictions={} evicted={}B",
                    bytes, limit(), RESIDENT.size(), peak, restores, restoredBytes, failures, evictions, evictedBytes);
        }
        lastLog = now;
        peak = bytes;
        evictions = evictedBytes = restores = restoredBytes = failures = 0;
    }
}
