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
                              Map<String, IrControlSound> controls, float ridingVolume,
                              boolean hornSustained, boolean toggleBell, float enginePitchRange) {
    public static final IrVehicleSounds NONE = new IrVehicleSounds(null, null, null, null, null, null,
            Set.of(), Set.of(), Set.of(), Map.of(), 1, false, true, 0.25f);

    public IrVehicleSounds {
        engineGroups = Set.copyOf(engineGroups);
        hornGroups = Set.copyOf(hornGroups);
        bellGroups = Set.copyOf(bellGroups);
        controls = Map.copyOf(controls);
    }

    public IrControlDefinition.Type controlType(String group, IrControlDefinition.Type authored) {
        if (hornGroups.contains(group)) return IrControlDefinition.Type.PRESS;
        if (bellGroups.contains(group)) return toggleBell ? IrControlDefinition.Type.TOGGLE : IrControlDefinition.Type.PRESS;
        return authored;
    }

    public boolean heldButton(String group) { return hornGroups.contains(group) || bellGroups.contains(group) && !toggleBell; }
}
