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

/** Vehicle assembly referencing shared source geometry. */
public record LoadedModel(List<Instance> instances, ModelBounds bounds) {
    /** Owns GPU geometry; selections only reference its parts and never own rendering resources. */
    public record Source(List<ModelPart> parts, PartSelection visibleParts,
                         Map<String, PartSelection> partsByName, Map<VehiclePartRole, PartSelection> componentParts,
                         GpuModel gpu) {}
    public record Instance(Source model, VehicleDefinition.Instance definition, PartSelection visibleParts) {
        public ModelData.Position offset() { return definition.offset(); }
    }
}
