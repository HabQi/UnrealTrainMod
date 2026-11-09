/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record VehicleDefinition(ResourceLocation id, String name, PackageAsset config, PackageAsset buttonTexture, String trainType,
                                List<Instance> parts, ModelBounds bounds, VehicleMotionSpec motionSpec,
                                VehiclePerformanceSpec performanceSpec, VehicleInteriorSpec interiorSpec,
                                IrVehicleDefinition ir) {
    public VehicleDefinition {
        parts = List.copyOf(parts);
        if (ir != IrVehicleDefinition.NONE) ir = ir.withMaxSpeedKmh(performanceSpec.maxSpeed() * 3.6);
    }
    /**
     * Bounds and pivot are source-local; offset retains the original static assembly. Door actions belong to
     * the body instance; bogie instances carry {@link VehicleActions#NONE}.
     */
    public record Instance(ModelSource source, ModelData.Position offset, VehiclePartRole role,
                           TrackVector pivot, ModelBounds bounds, VehicleActions actions) {
        public Instance {
            if (actions == null) actions = VehicleActions.NONE;
        }

        /** RTM mounts the same bogie model facing opposite directions at the two ends. */
        public boolean reversedBogie() {
            return source.format() == ModelSource.Format.MQO && role == VehiclePartRole.REAR_BOGIE;
        }
    }

    public VehicleDefinition withMotionSpec(VehicleMotionSpec spec) {
        var calibrated = parts.stream().map(part -> {
            if (part.source().format() != ModelSource.Format.IR_OBJ || part.role() == VehiclePartRole.BODY) return part;
            double z = part.role() == VehiclePartRole.FRONT_BOGIE
                    ? spec.frontBogieOffset() : spec.rearBogieOffset();
            return new Instance(part.source(), part.offset(), part.role(),
                    new TrackVector(0, 0, z), part.bounds(), part.actions());
        }).toList();
        return new VehicleDefinition(id, name, config, buttonTexture, trainType, calibrated, bounds, spec, performanceSpec,
                interiorSpec, ir);
    }

    public VehicleDefinition withPerformanceSpec(VehiclePerformanceSpec spec) {
        return new VehicleDefinition(id, name, config, buttonTexture, trainType, parts, bounds, motionSpec, spec,
                interiorSpec, ir);
    }

    public VehicleDefinition withInteriorSpec(VehicleInteriorSpec spec) {
        return new VehicleDefinition(id, name, config, buttonTexture, trainType, parts, bounds, motionSpec,
                performanceSpec, spec, ir);
    }

    public String packName() { return config.archive().getFileName().toString(); }
    public float width() { return bounds.width(); }
    public float height() { return bounds.height(); }
    public float length() { return bounds.length(); }
}
