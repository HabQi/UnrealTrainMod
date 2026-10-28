/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import com.unrealtrainmod.UnrealTrainMod;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Startup catalog shared by the logical client and server; contains no rendering objects. */
public final class TrainModels {
    public static final float MQO_SCALE = 0.01f;
    public record Texture(RtmAsset asset, boolean alphaBlend) {}
    public record Source(RtmAsset model, Map<String, Texture> textures,
                         Map<String, MaterialRenderMode> materialRenderModes, boolean smoothing, boolean cullFaces) {
        public Source {
            textures = Map.copyOf(textures);
            materialRenderModes = Map.copyOf(materialRenderModes);
        }
    }
    public record Instance(Source source, ModelData.Position offset) {}
    public record Definition(ResourceLocation id, String name, String packName, String trainType,
                             List<Instance> parts, ModelBounds bounds) {
        public Definition { parts = List.copyOf(parts); }
        public float width() { return bounds.width(); }
        public float height() { return bounds.height(); }
        public float length() { return bounds.length(); }
    }

    private static List<Definition> models = List.of();
    private static Map<ResourceLocation, Definition> byId = Map.of();

    private TrainModels() {}

    static void register(List<Definition> definitions) {
        models = List.copyOf(definitions);
        byId = models.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Definition::id, model -> model));
    }

    public static List<Definition> all() { return models; }
    public static Definition find(ResourceLocation id) { return id == null ? null : byId.get(id); }

    public static ResourceLocation idForName(String name) {
        return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "rtm/" + hash(name));
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime has no SHA-256", e);
        }
    }
}
