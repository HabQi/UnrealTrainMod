/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.vehicle.VehiclePerformanceSpec;

/** One car's runtime brake actuator, in newtons. No air pipe or propagation simulation. */
public final class TrainBrakeState {
    private final VehiclePerformanceSpec spec;
    private double actualForce;

    TrainBrakeState(VehiclePerformanceSpec spec) {
        this.spec = spec;
        park();
    }

    public double actualForce() { return actualForce; }

    public double targetForce(TrainControlState control) {
        return control.emergencyBrake() ? spec.maxEmergencyBrakeForce() : spec.maxServiceBrakeForce() * control.trainBrake();
    }

    void advance(TrainControlState control) {
        double target = targetForce(control);
        if (target > actualForce) {
            double rate = control.emergencyBrake() ? spec.maxEmergencyBrakeForce() / spec.emergencyBrakeApplyTime()
                    : spec.maxServiceBrakeForce() / spec.serviceBrakeApplyTime();
            actualForce = Math.min(target, actualForce + rate * TrainDynamics.STEP);
        } else {
            double rate = spec.maxEmergencyBrakeForce() / spec.brakeReleaseTime();
            actualForce = Math.max(target, actualForce - rate * TrainDynamics.STEP);
        }
    }

    void park() { actualForce = spec.maxServiceBrakeForce(); }
}
