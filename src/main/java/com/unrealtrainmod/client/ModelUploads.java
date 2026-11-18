/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayDeque;

/** Shared first-use budget, also active in menus. Finished resources become visible at a frame boundary. */
public final class ModelUploads {
    public static final int SLICE_BYTES = 512 * 1024;
    private static final ArrayDeque<Task> TASKS = new ArrayDeque<>();
    private static final ArrayDeque<Runnable> COMMITS = new ArrayDeque<>();
    private static int slices, bytes;
    private static long elapsed, frame, uploadNanos, uploadBytes, packingNanos;

    public interface Task {
        /** Progress at most one bounded slice. True removes this task from the queue. */
        boolean step();
    }

    private ModelUploads() {}
    public static long frame() { return frame; }
    public static void enqueue(Task task) { TASKS.addLast(task); }
    public static void commit(Runnable commit) { COMMITS.addLast(commit); }
    public static boolean available() { return slices < 4 && bytes < 2 * 1024 * 1024 && elapsed < 2_000_000L; }
    public static boolean run(int size, Runnable operation) {
        if (!available() || size > SLICE_BYTES || bytes + size > 2 * 1024 * 1024) return false;
        long start = System.nanoTime();
        try { operation.run(); }
        finally {
            long nanos = System.nanoTime() - start;
            elapsed += nanos; uploadNanos += nanos; bytes += size; uploadBytes += size; slices++;
        }
        return true;
    }
    public static void packed(long nanos) { packingNanos += nanos; }

    public static void beginFrame() {
        RenderSystem.assertOnRenderThread();
        frame++;
        int commits = COMMITS.size();
        for (int i = 0; i < commits; i++) COMMITS.removeFirst().run();
        slices = bytes = 0; elapsed = 0;
        ModelPreparation.beginFrame();
        int idle = TASKS.size();
        while (idle > 0 && !TASKS.isEmpty() && available()) {
            int previous = slices;
            var task = TASKS.removeFirst();
            if (!task.step()) TASKS.addLast(task);
            idle = slices == previous ? idle - 1 : TASKS.size();
        }
    }
    static void clear() { TASKS.clear(); COMMITS.clear(); }

    static String statistics() {
        String result = "uploadsPending=" + TASKS.size() + " upload=" + uploadBytes + "B renderPrepareUpload="
                + uploadNanos / 1_000_000.0 + "ms renderPacking=" + packingNanos / 1_000_000.0 + "ms";
        uploadNanos = uploadBytes = packingNanos = 0;
        return result;
    }
}
