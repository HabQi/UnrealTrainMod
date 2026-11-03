/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/** Readable legacy state types; unsupported ids fail instead of masquerading as another state. */
public final class RtmTrainState {
    private RtmTrainState() {}

    public enum TrainStateType {
        State_TrainDir(0), State_Notch(1), State_Door(4), State_Announcement(9);

        public final int id;

        TrainStateType(int id) { this.id = id; }
    }

    public static TrainStateType getStateType(int id) {
        for (var type : TrainStateType.values()) if (type.id == id) return type;
        throw new UnsupportedOperationException("Unsupported RTM vehicle state type " + id);
    }
}
