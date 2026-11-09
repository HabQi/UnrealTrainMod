/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.sound.VehicleSoundSequence;
import java.util.Map;
import java.util.Set;

/** Vehicle sounds and original component bindings, independent of logical control-group renaming. */
public record IrVehicleSounds(VehicleSoundSequence wheels, VehicleSoundSequence idle,
                              VehicleSoundSequence running, VehicleSoundSequence horn,
                              VehicleSoundSequence bell, VehicleSoundSequence couple,
                              Set<String> engineGroups, Set<String> hornGroups, Set<String> bellGroups,
                              Map<String, IrControlSound> controls, float ridingVolume) {
    public static final IrVehicleSounds NONE = new IrVehicleSounds(null, null, null, null, null, null,
            Set.of(), Set.of(), Set.of(), Map.of(), 1);

    public IrVehicleSounds {
        engineGroups = Set.copyOf(engineGroups);
        hornGroups = Set.copyOf(hornGroups);
        bellGroups = Set.copyOf(bellGroups);
        controls = Map.copyOf(controls);
    }
}
