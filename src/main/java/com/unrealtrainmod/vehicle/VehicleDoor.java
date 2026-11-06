/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

/** Left and right are defined by the vehicle's own forward direction. */
public enum VehicleDoor {
    LEFT, RIGHT;

    /** Driver-relative sides and authored sides share the same inverse conversion. */
    public VehicleDoor inFrame(int direction) {
        return direction > 0 ? this : this == LEFT ? RIGHT : LEFT;
    }
}
