/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.vehicle.VehicleCatalog;

/** Power-limited traction and independent brake response; movement acceptance remains in TrainManager. */
public final class TrainDynamics {
    public static final double STEP = 0.05;
    private static final double SPEED_TRACKING_TIME = 2;
    private TrainDynamics() {}

    static double advance(TrainConsist train) {
        double oldSpeed = train.actualSpeed();
        double magnitude = Math.abs(oldSpeed);
        double mass = 0, availableTraction = 0, serviceBrake = 0, rolling = 0, drag = 0;
        double slopeForce = 0, weightedGrade = 0, maxSpeed = TrainConsist.MAX_SPEED;
        for (var car : train.cars()) {
            var spec = VehicleCatalog.find(car.modelId()).performanceSpec();
            var tangent = train.sampler().sample(train.progress() + car.offset()).tangent();
            mass += spec.mass();
            if (spec.ratedPower() > 0) {
                availableTraction += magnitude == 0 ? spec.maxTractiveEffort()
                        : Math.min(spec.maxTractiveEffort(), spec.ratedPower() / magnitude);
            }
            serviceBrake += spec.maxServiceBrakeForce();
            rolling += spec.mass() * spec.rollingResistance();
            drag += spec.dragCoefficient() * oldSpeed * oldSpeed;
            slopeForce -= spec.mass() * 9.80665 * tangent.y();
            weightedGrade += spec.mass() * tangent.y() / Math.hypot(tangent.x(), tangent.z());
            maxSpeed = Math.min(maxSpeed, spec.maxSpeed());
        }
        if (train.commandControlled()) {
            double target = Math.clamp(train.commandTarget(), -maxSpeed, maxSpeed);
            train.setControl(commandControl(target, oldSpeed, mass, availableTraction, serviceBrake, rolling + drag, slopeForce));
        }
        var control = train.control();
        double actualBrake = 0;
        for (var car : train.cars()) {
            var brake = train.dynamics().brake(car.entityId());
            brake.advance(control);
            actualBrake += brake.actualForce();
        }
        // A P notch may be selected during release, but traction starts only after every car has released.
        double actualTraction = actualBrake > 0 || control.emergencyBrake() ? 0
                : availableTraction * control.throttle() * control.reverser().direction();
        train.dynamics().updateForces(actualTraction, actualBrake, Math.abs(actualTraction * oldSpeed));
        double drive = slopeForce + actualTraction;
        double resistance = rolling + drag + actualBrake;
        double direction = oldSpeed != 0 ? Math.signum(oldSpeed) : Math.signum(drive);
        double acceleration = (drive - resistance * direction) / mass;
        double speed;
        if (oldSpeed == 0 && Math.abs(drive) <= resistance) {
            speed = 0;
        } else {
            speed = oldSpeed + acceleration * STEP;
            // Integrate to a stop, then allow gravity/traction to start in either direction next tick.
            if (oldSpeed != 0 && speed * oldSpeed <= 0) speed = 0;
            speed = Math.clamp(speed, -maxSpeed, maxSpeed);
            if (Math.abs(speed) < 1e-7) speed = 0;
        }
        train.dynamics().update(speed, (speed - oldSpeed) / STEP, weightedGrade / mass);
        return (oldSpeed + speed) * 0.5 * STEP;
    }

    private static TrainControlState commandControl(double target, double speed, double mass, double traction,
                                                    double serviceBrake, double resistance, double slopeForce) {
        var reverser = target < 0 ? TrainControlState.Reverser.REVERSE : TrainControlState.Reverser.FORWARD;
        // Stopping and a direction change always brake first, using the same actuators as the driver.
        if (target == 0 || speed * target < 0) return new TrainControlState(reverser, 0, 1, false);
        double direction = reverser.direction();
        double motionDirection = speed != 0 ? Math.signum(speed) : direction;
        double requestedForce = mass * (target - speed) / SPEED_TRACKING_TIME
                - slopeForce + resistance * motionDirection;
        if (requestedForce * direction >= 0) {
            double throttle = traction == 0 ? 0 : Math.clamp(requestedForce * direction / traction, 0, 1);
            return new TrainControlState(reverser, throttle, 0, false);
        }
        double brake = serviceBrake == 0 ? 0 : Math.clamp(-requestedForce * direction / serviceBrake, 0, 1);
        return new TrainControlState(reverser, 0, brake, false);
    }
}
