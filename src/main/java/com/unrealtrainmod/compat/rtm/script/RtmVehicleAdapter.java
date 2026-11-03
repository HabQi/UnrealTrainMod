/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.train.TrainControlProfile;
import com.unrealtrainmod.vehicle.VehicleDoor;
import com.unrealtrainmod.vehicle.VehiclePartRole;

/** Samples native state into one script slot's view without retaining native game objects. */
final class RtmVehicleAdapter {
    private final RtmVehicleView view;
    private final VehiclePartRole role;

    RtmVehicleAdapter(PackageAsset script, VehiclePartRole role) {
        view = new RtmVehicleView(script);
        this.role = role;
    }

    RtmVehicleView sample(TrainEntity entity, float partialTick, float wheelRotation, int light) {
        if (entity == null) return null;
        var state = entity.runtimeState();
        int notch = state.emergencyBrake() ? -TrainControlProfile.BRAKE_NOTCHES
                : state.trainBrake() > 0 ? -(int)Math.round(state.trainBrake() * TrainControlProfile.BRAKE_NOTCHES)
                : (int)Math.round(state.throttle() * TrainControlProfile.POWER_NOTCHES);
        int doorState = (entity.isDoorOpen(VehicleDoor.RIGHT) ? 1 : 0)
                | (entity.isDoorOpen(VehicleDoor.LEFT) ? 2 : 0);
        boolean body = role == VehiclePartRole.BODY;
        view.update(entity.tickCount, light, (float)(state.speed() / 20.0), notch,
                state.reverser() < 0 ? 1 : 0, doorState, wheelRotation,
                body ? entity.doorMovement(VehicleDoor.LEFT, partialTick) : 0,
                body ? entity.doorMovement(VehicleDoor.RIGHT, partialTick) : 0);
        return view;
    }
}
