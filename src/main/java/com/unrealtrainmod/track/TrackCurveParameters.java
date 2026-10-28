/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

/** An unoriented horizontal tangent axis, using Minecraft yaw, and its Bezier handle length. */
public record TrackCurveParameters(double angle, double length) {
    public static final double MIN_LENGTH = 0.01;
    public static final double MAX_LENGTH = 256;

    public TrackCurveParameters {
        if (!Double.isFinite(angle) || !Double.isFinite(length) || length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("Curve angle must be finite; handle length must be in [0.01, 256]");
        }
        angle = ((angle % 360) + 540) % 360 - 180;
    }
}
