/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.vehicle.VehicleAction;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Vehicle assembly referencing shared source geometry. */
public record LoadedModel(List<Instance> instances, ModelBounds bounds, IrVehicleDefinition ir) {
    /**
     * Owns GPU geometry; selections only reference its parts and never own rendering resources.
     * {@code bodyParts} maps an action-name set to the body selection that omits those objects.
     * {@code actionSelections} resolves every action node by identity to its ordered object selection.
     */
    public record Source(List<ModelPart> parts, PartSelection visibleParts,
                         Map<Set<String>, PartSelection> bodyParts,
                         Map<VehicleAction, ActionSelection> actionSelections,
                         Map<String, PartSelection> partsByName, Map<IrVehicleDefinition, IrModelBinding> irBindings,
                         GpuModel gpu) {}
    public record Instance(Source model, VehicleDefinition.Instance definition, PartSelection visibleParts, IrModelBinding irBinding) {
        public ModelData.Position offset() { return definition.offset(); }
    }
}
