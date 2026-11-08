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
                               float diameter, IrReadout readout, float rangeMin, float rangeMax) {
    public enum Kind { WHEEL, THROTTLE, TRAIN_BRAKE, REVERSER, CENTERED, CONTROL, GAUGE }

    public IrModelComponent { objects = List.copyOf(objects); }

    public float value(float position) {
        if (kind == Kind.GAUGE) position = Math.clamp((position - rangeMin) / (rangeMax - rangeMin), 0, 1);
        float value = position + offset;
        if (invert) value = 1 - value;
        return kind == Kind.REVERSER || kind == Kind.CENTERED ? value - 0.5f : value;
    }

}
