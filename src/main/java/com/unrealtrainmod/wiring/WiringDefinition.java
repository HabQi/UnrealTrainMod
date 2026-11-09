/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.resource.PackageAsset;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Startup metadata and authored geometry. Wire positions are already measured in blocks. */
public record WiringDefinition(ResourceLocation id, String name, boolean wire, PackageAsset config,
        PackageAsset buttonTexture, PackageAsset model, ModelData geometry, Map<String, Texture> textures,
        PackageAsset renderer, boolean smoothing, boolean culling, String tags, ModelBounds bounds,
        Vec3 wirePos, String connectorType, double sectionLength, double deflection, double lengthCoefficient,
        double yOffset, boolean useCustomColor) {
    public record Texture(PackageAsset asset, List<String> flags) {
        public Texture { flags = List.copyOf(flags); }
        public boolean transparent() { return flags.contains("AlphaBlend"); }
        public boolean light() { return flags.contains("Light"); }
    }
    public WiringDefinition { textures = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(textures)); }
    public boolean empty() { return geometry.faceCount() == 0; }
}
