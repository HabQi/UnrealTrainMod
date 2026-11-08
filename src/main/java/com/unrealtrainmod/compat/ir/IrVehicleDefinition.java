/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.vehicle.VehicleRuntimeState;
import java.util.List;
import java.util.Map;

/** Logical control, animation and sound definitions contain no client runtime objects. */
public record IrVehicleDefinition(Map<String, IrControlDefinition> controls,
                                   List<IrAnimationDefinition> animations, float maxSpeedKmh, IrVehicleSounds sounds) {
    public static final IrVehicleDefinition NONE = new IrVehicleDefinition(Map.of(), List.of(), 200, IrVehicleSounds.NONE);

    public IrVehicleDefinition {
        controls = Map.copyOf(controls);
        animations = List.copyOf(animations);
    }

    public float value(String group, Map<String, Float> values, VehicleRuntimeState state, boolean front, boolean rear) {
        var control = controls.get(group);
        if (control == null) return 0;
        // A preview has no authoritative driving state and uses the authored defaults.
        if (state == null) return control.defaultValue();
        return control.readOnly() ? control.driver().sample(state, maxSpeedKmh, front, rear)
                : values.getOrDefault(group, control.defaultValue());
    }
}
