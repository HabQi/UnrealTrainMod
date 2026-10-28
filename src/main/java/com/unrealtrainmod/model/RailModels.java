/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import com.unrealtrainmod.UnrealTrainMod;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Startup resource catalog on both logical sides. It neither owns render resources nor describes track topology. */
public final class RailModels {
    public record Texture(RtmAsset asset, List<String> flags) {
        public Texture { flags = List.copyOf(flags); }
    }

    /** Raw authored MQO geometry, including hidden parts. Shared data must not be modified by consumers. */
    public record Source(RtmAsset model, ModelData data, Map<String, Texture> textures, RtmAsset rendererPath) {
        public Source { textures = Map.copyOf(textures); }
    }

    /** Legacy package values only; no block-state conversion or ballast generation is implied. */
    public record Ballast(String blockName, int blockMetadata, double height) {}

    /** Optional resource references are null; absent strings and lists are empty. No gauge is inferred. */
    public record Definition(ResourceLocation id, String railName, String packName, String configPath, Source source,
                             RtmAsset buttonTexture, String accuracy, String tags, List<Ballast> defaultBallast) {
        public Definition { defaultBallast = List.copyOf(defaultBallast); }
    }

    private static List<Definition> models = List.of();
    private static Map<ResourceLocation, Definition> byId = Map.of();

    private RailModels() {}

    static void register(List<Definition> definitions) {
        models = List.copyOf(definitions);
        byId = models.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Definition::id, model -> model));
    }

    public static List<Definition> all() { return models; }
    public static Definition find(ResourceLocation id) { return id == null ? null : byId.get(id); }

    public static ResourceLocation idForName(String name) {
        return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "rtm/rail/" + RtmResources.hash(name));
    }
}
