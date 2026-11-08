/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** One direct vehicle range per sample, not a full view or a whole frame. */
final class IrisGpuTimer implements AutoCloseable {
    private static final int PAIRS = 4, INTERVAL = 16;
    private final boolean supported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
    private final int[] starts = new int[PAIRS], ends = new int[PAIRS];
    private final boolean[] pending = new boolean[PAIRS], eligible = new boolean[PAIRS];
    private final long[] windows = new long[PAIRS];
    private int active = -1, untilSample, samples;
    private long nanos, max;

    void poll(long window) {
        if (!supported) return;
        for (int i = 0; i < PAIRS; i++) {
            if (!pending[i] || GL15.glGetQueryObjecti(ends[i], GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_FALSE) continue;
            pending[i] = false;
            if (!eligible[i] || windows[i] != window) continue;
            long elapsed = GL33.glGetQueryObjecti64(ends[i], GL15.GL_QUERY_RESULT)
                    - GL33.glGetQueryObjecti64(starts[i], GL15.GL_QUERY_RESULT);
            samples++;
            nanos += elapsed;
            max = Math.max(max, elapsed);
        }
    }

    void begin(long window) {
        if (!supported) return;
        if (untilSample > 0) { untilSample--; return; }
        untilSample = INTERVAL - 1;
        for (int i = 0; i < PAIRS; i++) {
            if (pending[i]) continue;
            if (starts[i] == 0) {
                starts[i] = GL15.glGenQueries();
                ends[i] = GL15.glGenQueries();
            }
            windows[i] = window;
            GL33.glQueryCounter(starts[i], GL33.GL_TIMESTAMP);
            active = i;
            return;
        }
    }

    void end(boolean include) {
        if (active < 0) return;
        GL33.glQueryCounter(ends[active], GL33.GL_TIMESTAMP);
        pending[active] = true;
        eligible[active] = include;
        active = -1;
    }

    void resetWindow() { samples = 0; nanos = max = 0; }

    String summary() {
        String status = !supported ? "unsupported" : samples == 0 ? "noSamples" : "sampled";
        return status + " samples=" + samples + " avg="
                + (samples == 0 ? "n/a" : String.format(Locale.ROOT, "%.3fms", nanos / (samples * 1e6)))
                + " max=" + (samples == 0 ? "n/a" : String.format(Locale.ROOT, "%.3fms", max / 1e6));
    }

    @Override public void close() {
        for (int i = 0; i < PAIRS; i++) {
            if (starts[i] != 0) GL15.glDeleteQueries(starts[i]);
            if (ends[i] != 0) GL15.glDeleteQueries(ends[i]);
        }
    }
}
