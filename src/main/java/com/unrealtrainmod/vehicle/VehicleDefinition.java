/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.resource.PackageAsset;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record VehicleDefinition(ResourceLocation id, String name, PackageAsset config, PackageAsset buttonTexture, String trainType,
                                List<Instance> parts, ModelBounds bounds, VehicleMotionSpec motionSpec) {
    public VehicleDefinition { parts = List.copyOf(parts); }
    public record Instance(ModelSource source, ModelData.Position offset) {}

    public String packName() { return config.archive().getFileName().toString(); }
    public float width() { return bounds.width(); }
    public float height() { return bounds.height(); }
    public float length() { return bounds.length(); }
}
