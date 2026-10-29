/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.vehicle.VehiclePartRole;

/** IR's source front (-X) becomes the imported model's rear (-Z). */
public final class IrBogieGroups {
    private IrBogieGroups() {}

    public static VehiclePartRole role(String name) {
        if (matches(name, "BOGEY_FRONT")) return VehiclePartRole.REAR_BOGIE;
        if (matches(name, "BOGEY_REAR")) return VehiclePartRole.FRONT_BOGIE;
        return VehiclePartRole.BODY;
    }

    private static boolean matches(String name, String prefix) {
        return name.equals(prefix) || name.startsWith(prefix + "_");
    }
}
