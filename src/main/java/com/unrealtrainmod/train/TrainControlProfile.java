/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

/** One combined handle: B8 ... B1, N, P1 ... P5. */
public final class TrainControlProfile {
    public static final int BRAKE_NOTCHES = 8;
    public static final int POWER_NOTCHES = 5;
    private TrainControlProfile() {}

    public static boolean validHandle(int handle) { return handle >= -BRAKE_NOTCHES && handle <= POWER_NOTCHES; }

    public static TrainControlState control(int handle, TrainControlState.Reverser reverser, boolean emergency) {
        return new TrainControlState(reverser, emergency ? 0 : Math.max(0, handle) / (double) POWER_NOTCHES,
                Math.max(0, -handle) / (double) BRAKE_NOTCHES, emergency);
    }

    public static String notch(int handle) { return handle == 0 ? "N" : handle > 0 ? "P" + handle : "B" + -handle; }
}
