/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.vehicle.VehicleMotionSpec;

/** A vehicle's authored ends, independent of its orientation within a consist. */
public enum TrainCarEnd {
    FRONT(1), REAR(-1);

    private final int sign;
    TrainCarEnd(int sign) { this.sign = sign; }
    public int sign() { return sign; }
    public double offset(VehicleMotionSpec spec) {
        return this == FRONT ? spec.frontCouplerOffset() : spec.rearCouplerOffset();
    }
}
