/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

/** Independent traction (0..5) and service brake (0..8) notches. */
public final class TrainControlProfile {
    public static final int BRAKE_NOTCHES = 8;
    public static final int POWER_NOTCHES = 5;
    private TrainControlProfile() {}

    public static TrainControlState control(int power, int brake, TrainControlState.Reverser reverser, boolean emergency) {
        return new TrainControlState(reverser, emergency ? 0 : power / (double) POWER_NOTCHES,
                brake / (double) BRAKE_NOTCHES, emergency);
    }

    public static int powerNotch(TrainControlState control) {
        return (int) Math.round(control.throttle() * POWER_NOTCHES);
    }

    public static int brakeNotch(TrainControlState control) {
        return (int) Math.round(control.trainBrake() * BRAKE_NOTCHES);
    }
}
