/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client;

import java.util.LinkedHashMap;
import org.slf4j.LoggerFactory;

/** Render-thread residency for reload-owned resources; evictions retain their CPU descriptions. */
public final class GpuResidency {
    public enum Kind { BASE_TEXTURE, NATIVE_GEOMETRY, PBR }
    private static final class Entry {
        final Kind kind;
        final Runnable release;
        long bytes, frame, nanos;
        Entry(Kind kind, Runnable release) {
            this.kind = kind;
            this.release = release;
        }
    }

    private static final LinkedHashMap<Object, Entry> RESIDENT = new LinkedHashMap<>(64, 0.75f, true);
    private static final long[] BYTES = new long[Kind.values().length];
    private static long frame, now = System.nanoTime(), lastLog = now;
    private static long uploads, uploadedBytes, evictions, evictedBytes;

    private GpuResidency() {}

    public static void beginFrame() {
        frame++;
        now = System.nanoTime();
    }

    public static void uploaded(Object owner, Kind kind, long bytes, Runnable release) {
        var entry = RESIDENT.get(owner);
        if (entry == null) {
            entry = new Entry(kind, release);
            RESIDENT.put(owner, entry);
        }
        long added = bytes - entry.bytes;
        BYTES[kind.ordinal()] += added;
        uploadedBytes += added;
        uploads++;
        entry.bytes = bytes;
        entry.frame = frame;
        entry.nanos = now;
    }

    public static void used(Object owner) {
        var entry = RESIDENT.get(owner);
        if (entry != null) {
            entry.frame = frame;
            entry.nanos = now;
        }
    }

    public static void released(Object owner) {
        var entry = RESIDENT.remove(owner);
        if (entry != null) BYTES[entry.kind.ordinal()] -= entry.bytes;
    }

    /** Only frame-end eviction is allowed: deferred transparency and previews may still hold references. */
    public static void endFrame() {
        long baseLimit = (long) ClientConfig.BASE_TEXTURE_CACHE_MIB.get() * 1024 * 1024;
        long nativeLimit = (long) ClientConfig.NATIVE_GEOMETRY_CACHE_MIB.get() * 1024 * 1024;
        long pbrLimit = (long) ClientConfig.PBR_TEXTURE_CACHE_MIB.get() * 1024 * 1024;
        double retention = ClientConfig.GPU_CACHE_RETENTION_SECONDS.get();
        if (BYTES[0] > baseLimit || BYTES[1] > nativeLimit || BYTES[2] > pbrLimit) {
            long idleBefore = now - (long) (retention * 1_000_000_000L);
            var iterator = RESIDENT.values().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long limit = switch (entry.kind) {
                    case BASE_TEXTURE -> baseLimit;
                    case NATIVE_GEOMETRY -> nativeLimit;
                    case PBR -> pbrLimit;
                };
                if (BYTES[entry.kind.ordinal()] <= limit || entry.frame >= frame - 1 || entry.nanos > idleBefore) continue;
                iterator.remove();
                BYTES[entry.kind.ordinal()] -= entry.bytes;
                evictions++;
                evictedBytes += entry.bytes;
                entry.release.run();
            }
        }
        if (now - lastLog < 5_000_000_000L) return;
        if (ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean() || ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) {
            LoggerFactory.getLogger("UnrealTrainMod/GPU").info(
                    "GPU residency: base={}B/{}B native={}B/{}B pbr={}B/{}B idleRetention={}s entries={} uploads={} upload={}B evictions={} evicted={}B (logical bytes; excludes Iris geometry, track buffers and render targets)",
                    BYTES[0], baseLimit, BYTES[1], nativeLimit, BYTES[2], pbrLimit,
                    retention, RESIDENT.size(), uploads, uploadedBytes, evictions, evictedBytes);
        }
        lastLog = now;
        uploads = uploadedBytes = evictions = evictedBytes = 0;
    }
}
