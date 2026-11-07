/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Vehicle assembly referencing shared source geometry. */
public record LoadedModel(List<Instance> instances, ModelBounds bounds, Map<String, Float> irControlDefaults) {
    /**
     * Owns GPU geometry; selections only reference its parts and never own rendering resources.
     * {@code bodyParts} maps an action-name set to the body selection that omits those objects.
     * {@code actionParts} groups all objects with the same normalized action name.
     */
    public record Source(List<ModelPart> parts, PartSelection visibleParts,
                         Map<Set<String>, PartSelection> bodyParts,
                         Map<String, PartSelection> actionParts,
                         Map<String, PartSelection> partsByName, Map<VehiclePartRole, PartSelection> componentParts,
                         Map<VehiclePartRole, List<IrModelPart>> irParts,
                         GpuModel gpu) {}
    public record Instance(Source model, VehicleDefinition.Instance definition, PartSelection visibleParts) {
        public ModelData.Position offset() { return definition.offset(); }
    }
}
