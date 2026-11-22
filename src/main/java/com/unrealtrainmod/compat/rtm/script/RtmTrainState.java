/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.train.TrainControlProfile;
import com.unrealtrainmod.vehicle.VehicleRuntimeState;

/** Readable legacy state types; unsupported ids fail instead of masquerading as another state. */
public final class RtmTrainState {
    private static final TrainStateType[] TYPES = TrainStateType.values();

    private RtmTrainState() {}

    public enum TrainStateType {
        State_TrainDir(0), State_Notch(1), State_Door(4), State_Announcement(9), State_Direction(10);

        public final int id;

        TrainStateType(int id) { this.id = id; }
    }

    public static TrainStateType getStateType(int id) {
        for (var type : TYPES) if (type.id == id) return type;
        throw new UnsupportedOperationException("Unsupported RTM vehicle state type " + id);
    }

    public static int notch(VehicleRuntimeState state) {
        if (state.emergencyBrake()) return -TrainControlProfile.BRAKE_NOTCHES;
        if (state.trainBrake() > 0) return -(int)Math.round(state.trainBrake() * TrainControlProfile.BRAKE_NOTCHES);
        return (int)Math.round(state.throttle() * TrainControlProfile.POWER_NOTCHES);
    }
}
