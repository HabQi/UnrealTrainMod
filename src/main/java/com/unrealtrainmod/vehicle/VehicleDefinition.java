/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record VehicleDefinition(ResourceLocation id, String name, PackageAsset config, PackageAsset buttonTexture, String trainType,
                                List<Instance> parts, ModelBounds bounds, VehicleMotionSpec motionSpec) {
    public VehicleDefinition { parts = List.copyOf(parts); }
    /** Bounds and pivot are source-local; offset retains the original static assembly. */
    public record Instance(ModelSource source, ModelData.Position offset, VehiclePartRole role,
                           TrackVector pivot, ModelBounds bounds) {}

    public VehicleDefinition withMotionSpec(VehicleMotionSpec spec) {
        var calibrated = parts.stream().map(part -> {
            if (part.source().format() != ModelSource.Format.IR_OBJ || part.role() == VehiclePartRole.BODY) return part;
            double z = part.role() == VehiclePartRole.FRONT_BOGIE
                    ? spec.frontBogieOffset() : spec.rearBogieOffset();
            return new Instance(part.source(), part.offset(), part.role(),
                    new TrackVector(0, 0, z), part.bounds());
        }).toList();
        return new VehicleDefinition(id, name, config, buttonTexture, trainType, calibrated, bounds, spec);
    }

    public String packName() { return config.archive().getFileName().toString(); }
    public float width() { return bounds.width(); }
    public float height() { return bounds.height(); }
    public float length() { return bounds.length(); }
}
