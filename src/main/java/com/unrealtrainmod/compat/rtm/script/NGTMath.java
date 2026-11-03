/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import net.minecraft.util.Mth;

/** Independently implemented scalar subset of the legacy animation math API. */
public final class NGTMath {
    public static final float PI = (float)Math.PI;

    private NGTMath() {}

    public static float toDegrees(float angle) { return (float)Math.toDegrees(angle); }
    public static double toDegrees(double angle) { return Math.toDegrees(angle); }
    public static float toRadians(float angle) { return (float)Math.toRadians(angle); }
    public static double toRadians(double angle) { return Math.toRadians(angle); }

    /** Degrees in [0, 360). */
    public static double normalizeAngle(double angle) {
        double remainder = angle % 360.0;
        return remainder < 0 ? remainder + 360.0 : remainder;
    }

    /** These two legacy names consume radians. */
    public static float getSin(float angle) { return Mth.sin(angle); }
    public static float getCos(float angle) { return Mth.cos(angle); }
    public static float getSinD(float angle) { return getSin(toRadians(angle)); }
    public static float getCosD(float angle) { return getCos(toRadians(angle)); }
    public static float sin(float angle) { return getSinD(angle); }
    public static float cos(float angle) { return getCosD(angle); }

    public static float getAtan2(double y, double x) { return (float)Mth.atan2(y, x); }
    public static float getAtan2D(double y, double x) { return toDegrees(getAtan2(y, x)); }
    public static float wrapAngle(float angle) { return Mth.wrapDegrees(angle); }
    public static int floor(float value) { return Mth.floor(value); }
    public static int floor(double value) { return Mth.floor(value); }
    public static int clamp(int value, int min, int max) { return Math.min(max, Math.max(min, value)); }
    public static byte clamp(byte value, byte min, byte max) { return (byte)clamp((int)value, min, max); }

    public static double sigmoid(double progress, double curvature) {
        if (progress <= 0) return 0;
        if (progress >= 1) return 1;
        double centered = curvature * (progress - 0.5);
        return 0.5 + 0.5 * centered / Math.sqrt(1 + centered * centered);
    }
}
