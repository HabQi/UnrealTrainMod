/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.model;

import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.resource.PackageAsset;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Static resource definition; track topology and rendering resources are owned elsewhere. */
public record RailDefinition(ResourceLocation id, String railName, PackageAsset config, Source source,
                             PackageAsset buttonTexture, String accuracy, String tags, List<Ballast> defaultBallast) {
    public RailDefinition { defaultBallast = List.copyOf(defaultBallast); }

    public record Texture(PackageAsset asset, List<String> flags) {
        public Texture { flags = List.copyOf(flags); }
    }

    /** Shared authored MQO geometry, including hidden parts. Consumers must not modify its arrays. */
    public record Source(PackageAsset model, ModelData data, Map<String, Texture> textures, PackageAsset rendererPath) {
        public Source { textures = Map.copyOf(textures); }
    }

    /** Legacy package values, without block-state conversion or ballast generation. */
    public record Ballast(String blockName, int blockMetadata, double height) {}

    public String packName() { return config.archive().getFileName().toString(); }
    public String configPath() { return config.entry(); }
}
