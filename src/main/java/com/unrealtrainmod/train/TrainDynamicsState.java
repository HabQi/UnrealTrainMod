/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.vehicle.VehicleCatalog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runtime only: SI motion/forces and independent brake actuators keyed by member identity. */
public final class TrainDynamicsState {
    private double speed;
    private double acceleration;
    private double grade;
    private double tractionForce;
    private double brakeForce;
    private double tractionPower;
    private final Map<UUID, TrainBrakeState> brakes = new HashMap<>();
    public double speed() { return speed; }
    public double acceleration() { return acceleration; }
    public double grade() { return grade; }
    public double tractionForce() { return tractionForce; }
    public double brakeForce() { return brakeForce; }
    public double tractionPower() { return tractionPower; }
    public TrainBrakeState brake(UUID member) { return brakes.get(member); }

    void resetBrakes(List<TrainCarState> cars) {
        brakes.clear();
        for (var car : cars) {
            var model = VehicleCatalog.find(car.modelId());
            // Missing-model consists remain readable; successful recovery initializes their actuators.
            if (model != null) brakes.put(car.entityId(), new TrainBrakeState(model.performanceSpec()));
        }
        updateForces(0, brakes.values().stream().mapToDouble(TrainBrakeState::actualForce).sum(), 0);
    }

    void updateForces(double tractionForce, double brakeForce, double tractionPower) {
        this.tractionForce = tractionForce;
        this.brakeForce = brakeForce;
        this.tractionPower = tractionPower;
    }

    void update(double speed, double acceleration, double grade) {
        this.speed = speed;
        this.acceleration = acceleration;
        this.grade = grade;
    }

    void stop() {
        update(0, 0, grade);
        for (var brake : brakes.values()) brake.park();
        updateForces(0, brakes.values().stream().mapToDouble(TrainBrakeState::actualForce).sum(), 0);
    }
}
