/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.vehicle.VehicleRuntimeState;

/** Values available from this mod's authoritative vehicle state. */
public enum IrReadout {
    SPEED, THROTTLE, REVERSER, TRAIN_BRAKE, COUPLED_FRONT, COUPLED_REAR, UNAVAILABLE;

    public float sample(VehicleRuntimeState state, float maxSpeedKmh, boolean front, boolean rear) {
        if (state == null) state = VehicleRuntimeState.IDLE;
        return switch (this) {
            case SPEED -> (float)(Math.abs(state.speed()) * 3.6 / maxSpeedKmh);
            case THROTTLE -> (float)state.throttle();
            case REVERSER -> (state.reverser() + 1) * 0.5f;
            case TRAIN_BRAKE -> state.emergencyBrake() ? 1 : (float)state.trainBrake();
            case COUPLED_FRONT -> front ? 1 : 0;
            case COUPLED_REAR -> rear ? 1 : 0;
            case UNAVAILABLE -> 0;
        };
    }
}
