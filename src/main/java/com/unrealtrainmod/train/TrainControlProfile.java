/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

/** Normalized controls with vehicle-specific traction notches and eight service brake notches. */
public final class TrainControlProfile {
    public static final int BRAKE_NOTCHES = 8;
    public static final int POWER_NOTCHES = 5;
    private TrainControlProfile() {}

    public static TrainControlState control(int power, int brake, TrainControlState.Reverser reverser, boolean emergency) {
        return control(power, brake, reverser, emergency, POWER_NOTCHES);
    }

    public static TrainControlState control(int power, int brake, TrainControlState.Reverser reverser, boolean emergency,
                                             int powerNotches) {
        return new TrainControlState(reverser, emergency ? 0 : power / (double) powerNotches,
                brake / (double) BRAKE_NOTCHES, emergency);
    }

    public static int powerNotch(TrainControlState control) {
        return powerNotch(control, POWER_NOTCHES);
    }

    public static int powerNotch(TrainControlState control, int powerNotches) {
        return (int) Math.round(control.throttle() * powerNotches);
    }

    public static int brakeNotch(TrainControlState control) {
        return (int) Math.round(control.trainBrake() * BRAKE_NOTCHES);
    }
}
