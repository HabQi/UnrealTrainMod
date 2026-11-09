/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm.script;

/** Legacy immediate quads are collected on the CPU, never sent to fixed-function OpenGL. */
public final class NGTTessellator {
    public static final NGTTessellator instance = new NGTTessellator();
    private NGTTessellator() {}
    private WireDrawContext context() {
        if (!(GL11.current() instanceof WireDrawContext context)) throw new IllegalStateException("Wire tessellator outside wire rendering");
        return context;
    }
    public void startDrawingQuads() { context().begin(); }
    public void addVertexWithUV(double x, double y, double z, double u, double v) { context().vertex(x, y, z, u, v); }
    public void addVertex(double x, double y, double z) { addVertexWithUV(x, y, z, 0, 0); }
    public int draw() { return context().end(); }
}
