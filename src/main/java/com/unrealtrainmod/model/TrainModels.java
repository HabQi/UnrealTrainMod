/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import com.unrealtrainmod.UnrealTrainMod;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Startup catalog shared by the logical client and server; contains no rendering objects. */
public final class TrainModels {
    public static final float MQO_SCALE = 0.01f;
    public enum Format { MQO, IR_OBJ }
    public record Texture(RtmAsset asset, boolean alphaBlend) {}
    public record Source(RtmAsset model, Map<String, Texture> textures,
                         Map<String, MaterialRenderMode> materialRenderModes, boolean smoothing, boolean cullFaces,
                         Format format, float scale) {
        public Source(RtmAsset model, Map<String, Texture> textures,
                      Map<String, MaterialRenderMode> materialRenderModes, boolean smoothing, boolean cullFaces) {
            this(model, textures, materialRenderModes, smoothing, cullFaces, Format.MQO, MQO_SCALE);
        }

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

    public static void load(Path modsDirectory) {
        var definitions = new ArrayList<>(RtmPackageLoader.load(modsDirectory));
        definitions.addAll(IrPackageLoader.load(modsDirectory));
        register(definitions);
    }

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
        return RtmResources.hash(value);
    }
}
