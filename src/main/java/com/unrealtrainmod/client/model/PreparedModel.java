/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.vehicle.VehicleAction;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU geometry and selections awaiting render-thread upload. */
public record PreparedModel(List<ModelPart> parts, PartSelection visibleParts,
                            Map<Set<String>, PartSelection> bodyParts,
                            Map<VehicleAction, ActionSelection> actionSelections,
                            Map<String, PartSelection> partsByName, Map<IrVehicleDefinition, IrModelBinding> irBindings,
                            List<RenderBatch> batches) {}
