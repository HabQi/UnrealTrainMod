/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Network-only native state. Speed is m/s; speed and reverser use this vehicle's front as positive. */
public record VehicleRuntimeState(double speed, double throttle, double trainBrake,
                                  boolean emergencyBrake, int reverser, int formationIndex, int formationSize) {
    public static final VehicleRuntimeState IDLE = new VehicleRuntimeState(0, 0, 0, false, 0, -1, 0);

    public VehicleRuntimeState withFormation(int index, int size) {
        if (index == formationIndex && size == formationSize) return this;
        return new VehicleRuntimeState(speed, throttle, trainBrake, emergencyBrake, reverser, index, size);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putDouble("Speed", speed);
        tag.putDouble("Throttle", throttle);
        tag.putDouble("TrainBrake", trainBrake);
        tag.putBoolean("EmergencyBrake", emergencyBrake);
        tag.putInt("Reverser", reverser);
        if (formationSize > 0) {
            tag.putInt("FormationIndex", formationIndex);
            tag.putInt("FormationSize", formationSize);
        }
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
        int index = -1, size = 0;
        if (tag.contains("FormationIndex", Tag.TAG_INT) && tag.contains("FormationSize", Tag.TAG_INT)) {
            int candidateIndex = tag.getInt("FormationIndex");
            int candidateSize = tag.getInt("FormationSize");
            if (candidateIndex >= 0 && candidateIndex < candidateSize) {
                index = candidateIndex;
                size = candidateSize;
            }
        }
        return new VehicleRuntimeState(speed, throttle, brake, tag.getBoolean("EmergencyBrake"), reverser, index, size);
    }

    private static double number(CompoundTag tag, String name) {
        if (!tag.contains(name, Tag.TAG_DOUBLE) || !Double.isFinite(tag.getDouble(name))) {
            throw new IllegalArgumentException("Invalid vehicle runtime state " + name);
        }
        return tag.getDouble(name);
    }
}
