/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.compat.ir.IrAnimatrix;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import java.util.List;
import java.util.Map;

/** One vehicle configuration's selections, all referencing shared source geometry. */
public record IrModelBinding(Map<VehiclePartRole, PartSelection> staticParts,
                              Map<VehiclePartRole, List<IrModelPart>> parts,
                              Map<Integer, IrAnimatrix> animations) {}
