/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/** Only the legacy state type used by the current vehicle-body sample. */
public final class RtmTrainState {
    private RtmTrainState() {}

    public enum TrainStateType {
        State_Announcement;

        public final int id = 9;
    }

    public static TrainStateType getStateType(int id) {
        if (id == 9) return TrainStateType.State_Announcement;
        throw new UnsupportedOperationException("Unsupported RTM vehicle state type " + id);
    }
}
