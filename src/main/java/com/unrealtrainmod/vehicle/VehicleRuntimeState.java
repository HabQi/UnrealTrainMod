/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Network-only native state. Speed is m/s; speed and reverser use this vehicle's front as positive. */
public record VehicleRuntimeState(double speed, double throttle, double trainBrake,
                                  boolean emergencyBrake, int reverser) {
    public static final VehicleRuntimeState IDLE = new VehicleRuntimeState(0, 0, 0, false, 0);

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putDouble("Speed", speed);
        tag.putDouble("Throttle", throttle);
        tag.putDouble("TrainBrake", trainBrake);
        tag.putBoolean("EmergencyBrake", emergencyBrake);
        tag.putInt("Reverser", reverser);
        return tag;
    }

    public static VehicleRuntimeState load(CompoundTag tag) {
        if (tag.isEmpty()) return IDLE;
        double speed = number(tag, "Speed");
        double throttle = number(tag, "Throttle");
        double brake = number(tag, "TrainBrake");
        if (!tag.contains("EmergencyBrake", Tag.TAG_BYTE) || !tag.contains("Reverser", Tag.TAG_INT)) {
            throw new IllegalArgumentException("Missing vehicle runtime control state");
        }
        int reverser = tag.getInt("Reverser");
        if (throttle < 0 || throttle > 1 || brake < 0 || brake > 1 || reverser < -1 || reverser > 1) {
            throw new IllegalArgumentException("Invalid vehicle runtime control state");
        }
        return new VehicleRuntimeState(speed, throttle, brake, tag.getBoolean("EmergencyBrake"), reverser);
    }

    private static double number(CompoundTag tag, String name) {
        if (!tag.contains(name, Tag.TAG_DOUBLE) || !Double.isFinite(tag.getDouble(name))) {
            throw new IllegalArgumentException("Invalid vehicle runtime state " + name);
        }
        return tag.getDouble(name);
    }
}
