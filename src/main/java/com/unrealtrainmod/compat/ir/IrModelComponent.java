/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import java.util.List;

/** Immutable visual rules in the same scaled coordinates as the imported vehicle mesh. */
public record IrModelComponent(String key, VehiclePartRole role, Kind kind, List<String> objects,
                               String controlGroup, TrackVector pivot, TrackVector axis, float degrees,
                               TrackVector translation, float offset, boolean invert, boolean hide,
                               float diameter) {
    public enum Kind { WHEEL, THROTTLE, TRAIN_BRAKE, REVERSER, CENTERED, CONTROL }

    public IrModelComponent { objects = List.copyOf(objects); }

    public float defaultPosition() {
        return kind == Kind.REVERSER || kind == Kind.CENTERED ? 0.5f : 0;
    }

    public float value(float position) {
        float value = position + offset;
        if (invert) value = 1 - value;
        return kind == Kind.REVERSER || kind == Kind.CENTERED ? value - 0.5f : value;
    }

    public boolean driven() {
        return kind == Kind.WHEEL || kind == Kind.THROTTLE || kind == Kind.TRAIN_BRAKE || kind == Kind.REVERSER;
    }
}
