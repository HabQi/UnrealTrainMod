/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/**
 * Minimal replacement for the LWJGL2 matrix calls used by legacy RTM scripts. It never touches the real
 * OpenGL fixed pipeline; every call is applied to the current script's PoseStack.
 */
public final class GL11 {
    private static final ThreadLocal<ScriptMatrixContext> CURRENT = new ThreadLocal<>();

    private GL11() {}

    static void attach(ScriptMatrixContext context) { CURRENT.set(context); }

    static void detach() { CURRENT.remove(); }

    static ScriptMatrixContext current() {
        var context = CURRENT.get();
        if (context == null) throw new IllegalStateException("RTM GL call outside a script render context");
        return context;
    }

    public static void glPushMatrix() { current().push(); }

    public static void glPopMatrix() { current().pop(); }

    public static void glTranslatef(float x, float y, float z) { current().translate(x, y, z); }

    public static void glTranslated(double x, double y, double z) {
        current().translate((float)x, (float)y, (float)z);
    }

    public static void glRotatef(float angle, float x, float y, float z) { current().rotate(angle, x, y, z); }

    public static void glRotated(double angle, double x, double y, double z) {
        current().rotate((float)angle, (float)x, (float)y, (float)z);
    }

    public static void glScalef(float x, float y, float z) { current().scale(x, y, z); }

    public static void glScaled(double x, double y, double z) {
        current().scale((float)x, (float)y, (float)z);
    }
}
