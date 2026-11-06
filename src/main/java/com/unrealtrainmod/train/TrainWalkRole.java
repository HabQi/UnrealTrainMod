/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

/** Ownership of a vehicle-local walking session. */
public enum TrainWalkRole {
    DRIVER, PASSENGER;

    public static TrainWalkRole byId(int id) {
        var values = values();
        return id >= 0 && id < values.length ? values[id] : PASSENGER;
    }
}
