/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Sample the track stage with timestamp pairs, without waiting or using a nested query target. */
final class TrackGpuTimer implements AutoCloseable {
    private static final int SAMPLE_INTERVAL = 16;
    private static final int QUERY_PAIRS = 4;
    private final boolean supported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
    private final int[] starts = new int[QUERY_PAIRS];
    private final int[] ends = new int[QUERY_PAIRS];
    private final boolean[] pending = new boolean[QUERY_PAIRS];
    private final boolean[] eligible = new boolean[QUERY_PAIRS];
    private final boolean[] animated = new boolean[QUERY_PAIRS];
    private final long[] windows = new long[QUERY_PAIRS];
    private int active = -1;
    private int framesUntilSample;

    boolean supported() { return supported; }

    void beginFrame(long window, TrackRenderStats stats) {
        if (!supported) return;
        for (int i = 0; i < QUERY_PAIRS; i++) {
            if (!pending[i] || GL15.glGetQueryObjecti(ends[i], GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_FALSE) continue;
            pending[i] = false;
            // A result belongs to the window and render path that submitted it, not the one polling it.
            if (eligible[i] && windows[i] == window) {
                long start = GL33.glGetQueryObjecti64(starts[i], GL15.GL_QUERY_RESULT);
                long end = GL33.glGetQueryObjecti64(ends[i], GL15.GL_QUERY_RESULT);
                stats.gpuSpan(end - start, animated[i]);
            }
        }
        if (framesUntilSample > 0) {
            framesUntilSample--;
            return;
        }
        framesUntilSample = SAMPLE_INTERVAL - 1;
        for (int i = 0; i < QUERY_PAIRS; i++) {
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
        // All pairs are still in flight. Skip this sample instead of synchronizing with the GPU.
    }

    void endFrame(boolean include, boolean animation) {
        if (active == -1) return;
        GL33.glQueryCounter(ends[active], GL33.GL_TIMESTAMP);
        eligible[active] = include;
        animated[active] = animation;
        pending[active] = true;
        active = -1;
    }

    @Override
    public void close() {
        for (int i = 0; i < QUERY_PAIRS; i++) {
            if (starts[i] != 0) GL15.glDeleteQueries(starts[i]);
            if (ends[i] != 0) GL15.glDeleteQueries(ends[i]);
        }
    }
}
