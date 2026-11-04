/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/** Records legacy lighting changes in the same context as GL11 matrix calls. */
public final class GLHelper {
    private GLHelper() {}

    public static void disableLighting() { GL11.current().directionalLighting(false); }

    public static void enableLighting() { GL11.current().directionalLighting(true); }

    public static void setLightmapMaxBrightness() { GL11.current().brightness(0xF000F0); }

    public static void setBrightness(int packedLight) { GL11.current().brightness(packedLight); }

    public static ScriptGLList generateGLList(Object ignored) { return new ScriptGLList(); }

    public static boolean isValid(ScriptGLList list) { return list != null && list.compiled && !list.deleted; }

    public static void startCompile(ScriptGLList list) { GL11.current().startCompile(list); }

    public static void endCompile() { GL11.current().endCompile(); }

    public static void callList(ScriptGLList list) { GL11.current().callList(list); }

    public static void deleteGLList(ScriptGLList list) { if (list != null) list.delete(); }
}
