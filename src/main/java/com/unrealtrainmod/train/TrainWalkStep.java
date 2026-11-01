/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.vehicle.VehicleInteriorSpec;

/** One bounded walking step shared by server authority and client prediction. */
public final class TrainWalkStep {
    public static final double STEP = 0.2;

    public record Point(double x, double z) {}

    private TrainWalkStep() {}

    public static Point move(VehicleInteriorSpec spec, double x, double z, double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length > 1) {
            dx /= length;
            dz /= length;
        }
        return new Point(spec.clampX(x + dx * STEP), spec.clampZ(z + dz * STEP));
    }
}
