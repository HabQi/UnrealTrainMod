/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.ClientConfig;
import java.util.Arrays;
import org.slf4j.LoggerFactory;

/** CPU work and owned uploads, separated from deferred submissions and GPU timings. */
public final class IrisRenderStats {
    public static final int VEHICLE = 0, TRACK = 1;
    private static final Window[] WINDOWS = {new Window("Vehicle"), new Window("Track")};
    private static Sample current;

    private static final class Window {
        final String name;
        final long[][] values = new long[2][11];
        final long[] viewFrame = {-1, -1};
        long start = System.nanoTime();
        Window(String name) { this.name = name; }
        void reset() {
            for (var slot : values) Arrays.fill(slot, 0);
            Arrays.fill(viewFrame, -1);
            start = System.nanoTime();
        }
    }

    public static final class Sample implements AutoCloseable {
        private final Sample previous = current;
        private final Window window;
        private final int slot;
        private final long begun = System.nanoTime();
        private long prepare, sort, draw, triangles, gpuDraws, cpuVertices, hits, misses, uploads;
        private Sample(Window window, boolean shadow) {
            this.window = window;
            slot = shadow ? 1 : 0;
            current = this;
        }
        @Override public void close() {
            current = previous;
            long[] v = window.values[slot];
            if (window.viewFrame[slot] != IrisGeometryCache.frame()) {
                window.viewFrame[slot] = IrisGeometryCache.frame();
                v[0]++;
            }
            v[1]++; v[2] += triangles; v[3] += gpuDraws; v[4] += cpuVertices;
            v[5] += hits; v[6] += misses; v[7] += prepare; v[8] += sort;
            v[9] += Math.max(0, System.nanoTime() - begun - prepare - sort); v[10] += uploads;
            if (System.nanoTime() - window.start < 5_000_000_000L) return;
            var main = window.values[0]; var shadow = window.values[1];
            LoggerFactory.getLogger("UnrealTrainMod/Iris").info(
                    "{} rendering [Iris cached]: main views={} submissions={} triangles={} directGpuDraws={} cpuVertexRefs={} cacheHit/Miss={}/{} cpuPrepare/Sort/Submit={}/{}/{}ms ownedUpload={}B; "
                            + "shadow views={} submissions={} triangles={} directGpuDraws={} cpuVertexRefs={} cacheHit/Miss={}/{} cpuPrepare/Sort/Submit={}/{}/{}ms ownedUpload={}B; cache={}B peak={}B pending={}",
                    window.name, main[0], main[1], main[2], main[3], main[4], main[5], main[6],
                    main[7] / 1e6, main[8] / 1e6, main[9] / 1e6, main[10],
                    shadow[0], shadow[1], shadow[2], shadow[3], shadow[4], shadow[5], shadow[6],
                    shadow[7] / 1e6, shadow[8] / 1e6, shadow[9] / 1e6, shadow[10],
                    IrisGeometryCache.bytes(), IrisGeometryCache.peakBytes(), IrisGeometryCache.pending());
            window.reset();
        }
    }

    private IrisRenderStats() {}
    public static Sample begin(int kind, boolean shadow) {
        boolean enabled = kind == VEHICLE ? ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean()
                : ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean();
        if (!enabled) { WINDOWS[kind].reset(); return null; }
        return new Sample(WINDOWS[kind], shadow);
    }
    public static long clock() { return current == null ? 0 : System.nanoTime(); }
    public static void prepare(long start) { if (current != null) current.prepare += Math.max(0, System.nanoTime() - start - current.draw); }
    public static void sort(long start) { if (current != null) current.sort += System.nanoTime() - start; }
    public static void submit(long start) { if (current != null) current.draw += System.nanoTime() - start; }
    public static void cache(boolean hit) { if (current != null) { if (hit) current.hits++; else current.misses++; } }
    public static void gpu(int triangles) { gpu(1, triangles); }
    public static void gpu(int calls, int triangles) { if (current != null) { current.gpuDraws += calls; current.triangles += triangles; } }
    public static void buffered(int triangles) { if (current != null) { current.triangles += triangles; current.cpuVertices += (long)triangles * 3; } }
    public static void owned(int triangles) { if (current != null) { current.gpuDraws++; current.triangles += triangles; current.cpuVertices += (long)triangles * 3; } }
    public static void upload(long bytes) { if (current != null) current.uploads += bytes; }
    public static void reset() { for (var window : WINDOWS) window.reset(); }
}
