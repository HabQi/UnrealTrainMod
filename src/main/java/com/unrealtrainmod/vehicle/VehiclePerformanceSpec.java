/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

/** SI units; one block is treated as one metre. Independent of model geometry. */
public record VehiclePerformanceSpec(double mass, double maxSpeed, double maxTractiveEffort,
                                     double maxServiceBrakeForce, double maxEmergencyBrakeForce,
                                     double rollingResistance, double ratedPower, double dragCoefficient,
                                     double serviceBrakeApplyTime, double emergencyBrakeApplyTime, double brakeReleaseTime) {
    public static final VehiclePerformanceSpec DEFAULT = new VehiclePerformanceSpec(40_000, 120 / 3.6,
            36_000, 40_000, 60_000, 0.02, 600_000, 4, 1, 0.25, 1.5);

    public VehiclePerformanceSpec {
        positive("mass", mass);
        positive("maxSpeed", maxSpeed);
        nonnegative("maxTractiveEffort", maxTractiveEffort);
        nonnegative("maxServiceBrakeForce", maxServiceBrakeForce);
        nonnegative("maxEmergencyBrakeForce", maxEmergencyBrakeForce);
        nonnegative("rollingResistance", rollingResistance);
        nonnegative("ratedPower", ratedPower);
        nonnegative("dragCoefficient", dragCoefficient);
        positive("serviceBrakeApplyTime", serviceBrakeApplyTime);
        positive("emergencyBrakeApplyTime", emergencyBrakeApplyTime);
        positive("brakeReleaseTime", brakeReleaseTime);
        if (maxEmergencyBrakeForce < maxServiceBrakeForce) {
            throw new IllegalArgumentException("maxEmergencyBrakeForce must be at least maxServiceBrakeForce");
        }
    }

    private static void positive(String field, double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(field + " must be finite and positive");
    }

    private static void nonnegative(String field, double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(field + " must be finite and nonnegative");
    }
}
