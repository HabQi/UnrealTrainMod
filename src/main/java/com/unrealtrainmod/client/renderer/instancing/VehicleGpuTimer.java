/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.instancing;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Nonblocking timestamp pairs for one vehicle pass, excluding unrelated world render stages. */
final class VehicleGpuTimer implements AutoCloseable {
    private static final int QUERY_PAIRS = 4, SAMPLE_INTERVAL = 16;
    private final boolean opaque;
    private final boolean supported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
    private final int[] starts = new int[QUERY_PAIRS], ends = new int[QUERY_PAIRS];
    private final boolean[] pending = new boolean[QUERY_PAIRS], eligible = new boolean[QUERY_PAIRS];
    private final long[] windows = new long[QUERY_PAIRS];
    private int active = -1, waitFrames;

    VehicleGpuTimer(boolean opaque) { this.opaque = opaque; }
    boolean supported() { return supported; }

    void begin(long window, InstancingStats stats) {
        if (!supported) return;
        for (int i = 0; i < starts.length; i++) {
            if (!pending[i] || GL15.glGetQueryObjecti(ends[i], GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_FALSE) continue;
            pending[i] = false;
            if (eligible[i] && windows[i] == window) {
                long start = GL33.glGetQueryObjecti64(starts[i], GL15.GL_QUERY_RESULT);
                long end = GL33.glGetQueryObjecti64(ends[i], GL15.GL_QUERY_RESULT);
                stats.gpuSample(opaque, end - start);
            }
        }
        if (waitFrames > 0) { waitFrames--; return; }
        waitFrames = SAMPLE_INTERVAL - 1;
        for (int i = 0; i < starts.length; i++) {
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

    @Override
    public void close() {
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] != 0) GL15.glDeleteQueries(starts[i]);
            if (ends[i] != 0) GL15.glDeleteQueries(ends[i]);
        }
    }
}
