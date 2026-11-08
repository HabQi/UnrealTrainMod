/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.sound.VehicleSoundSequence;
import java.util.Set;

/** Basic IR sounds and the actual control groups bound to engine and horn components. */
public record IrVehicleSounds(VehicleSoundSequence wheels, VehicleSoundSequence idle,
                              VehicleSoundSequence running, VehicleSoundSequence horn,
                              Set<String> engineGroups, Set<String> hornGroups) {
    public static final IrVehicleSounds NONE = new IrVehicleSounds(null, null, null, null, Set.of(), Set.of());

    public IrVehicleSounds {
        engineGroups = Set.copyOf(engineGroups);
        hornGroups = Set.copyOf(hornGroups);
    }
}
