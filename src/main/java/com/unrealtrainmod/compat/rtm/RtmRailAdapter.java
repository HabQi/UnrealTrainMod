/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.MqoParser;
import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.track.model.RailDefinition;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/** Startup-only raw geometry cache and RTM rail configuration conversion. */
final class RtmRailAdapter {
    private final Map<ResourceLocation, RailDefinition> definitions;
    private final Map<PackageAsset, ModelData> models = new HashMap<>();
    private final Map<PackageAsset, IOException> failedModels = new HashMap<>();
    private final LoadReport report;

    RtmRailAdapter(Map<ResourceLocation, RailDefinition> definitions, LoadReport report) {
        this.definitions = definitions;
        this.report = report;
    }

    void read(PackageArchive archive, String entry, String prefix) {
        var config = new PackageAsset(archive.path(), entry);
        try {
            JsonObject json;
            try (var reader = new InputStreamReader(archive.open(entry), StandardCharsets.UTF_8)) {
                json = GsonHelper.parse(reader);
            }
            String name = string(json.get("railName"), "railName");
            if (name.isBlank()) throw new IOException("railName must not be blank");
            var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "rtm/rail/" + ResourceIds.hash(name));
            var retained = definitions.get(id);
            if (retained != null) {
                report.duplicate(LoadReport.Kind.RTM_RAIL, id, config, retained.config());
                return;
            }
            JsonObject model = object(json.get("model"), "model");
            String modelFile = string(model.get("modelFile"), "model.modelFile");
            if (!modelFile.toLowerCase(Locale.ROOT).endsWith(".mqo")) {
                throw new IOException("model.modelFile: only text MQO models are supported: " + modelFile);
            }
            PackageAsset asset = asset(archive, prefix, "minecraft:models/" + modelFile, "model.modelFile");
            Map<String, RailDefinition.Texture> textures = textures(model, archive, prefix);
            PackageAsset renderer = optionalAsset(model, "rendererPath", "model.rendererPath", archive, prefix);
            PackageAsset button = optionalAsset(json, "buttonTexture", "buttonTexture", archive, prefix);
            String accuracy = optionalString(json, "accuracy"), tags = optionalString(json, "tags");
            List<RailDefinition.Ballast> ballast = ballast(json);
            // Read geometry last so invalid metadata cannot retain an otherwise unused model in the cache.
            ModelData data = modelData(asset, archive);
            for (String material : textures.keySet()) {
                if (data.materials().stream().noneMatch(value -> value.name().equals(material))) {
                    UnrealTrainMod.LOGGER.warn("RTM rail {}!{}: model.textures material '{}' matches no MQO material in {}",
                            archive.path(), entry, material, asset.entry());
                }
            }
            var source = new RailDefinition.Source(asset, data, textures, renderer);
            definitions.put(id, new RailDefinition(id, name, config,
                    source, button, accuracy, tags, ballast));
            report.registered(LoadReport.Kind.RTM_RAIL);
        } catch (IOException | RuntimeException exception) {
            report.failed(LoadReport.Kind.RTM_RAIL, config, exception);
        }
    }

    private ModelData modelData(PackageAsset asset, PackageArchive archive) throws IOException {
        ModelData data = models.get(asset);
        if (data != null) return data;
        IOException previous = failedModels.get(asset);
        if (previous != null) throw new IOException("model.modelFile: previous MQO parsing failed for " + asset.entry(), previous);
        try (var input = archive.open(asset.entry())) {
            data = MqoParser.parse(input, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            var failure = new IOException("model.modelFile " + asset.entry() + ": " + exception.getMessage(), exception);
            failedModels.put(asset, failure);
            throw failure;
        }
        models.put(asset, data);
        return data;
    }

    private static Map<String, RailDefinition.Texture> textures(JsonObject model, PackageArchive archive, String prefix)
            throws IOException {
        if (!model.has("textures")) return Map.of();
        JsonArray configured = array(model.get("textures"), "model.textures");
        var textures = new LinkedHashMap<String, RailDefinition.Texture>();
        for (int i = 0; i < configured.size(); i++) {
            String field = "model.textures[" + i + "]";
            JsonArray binding = array(configured.get(i), field);
            if (binding.size() < 2 || binding.size() > 3) {
                throw new IOException(field + " must contain a material name, texture path and optional flags string");
            }
            String material = string(binding.get(0), field + "[0]");
            if (material.isBlank()) throw new IOException(field + "[0]: material name must not be blank");
            PackageAsset image = asset(archive, prefix, string(binding.get(1), field + "[1]"), field + "[1]");
            List<String> flags = binding.size() == 2 ? List.of()
                    : Arrays.stream(string(binding.get(2), field + "[2]").split(",", -1))
                            .map(String::strip).filter(flag -> !flag.isEmpty()).toList();
            if (textures.putIfAbsent(material, new RailDefinition.Texture(image, flags)) != null) {
                throw new IOException(field + ": duplicate material binding '" + material + "'");
            }
        }
        return textures;
    }

    private static List<RailDefinition.Ballast> ballast(JsonObject json) throws IOException {
        if (!json.has("defaultBallast")) return List.of();
        JsonArray configured = array(json.get("defaultBallast"), "defaultBallast");
        var result = new ArrayList<RailDefinition.Ballast>();
        for (int i = 0; i < configured.size(); i++) {
            String field = "defaultBallast[" + i + "]";
            JsonObject layer = object(configured.get(i), field);
            String blockName = string(layer.get("blockName"), field + ".blockName");
            if (blockName.isBlank()) throw new IOException(field + ".blockName must not be blank");
            int metadata;
            try {
                metadata = number(layer.get("blockMetadata"), field + ".blockMetadata").getAsBigDecimal().intValueExact();
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IOException(field + ".blockMetadata must be an exact 32-bit integer", exception);
            }
            double height = number(layer.get("height"), field + ".height").getAsDouble();
            if (!Double.isFinite(height)) throw new IOException(field + ".height must be finite");
            result.add(new RailDefinition.Ballast(blockName, metadata, height));
        }
        return result;
    }

    private static PackageAsset optionalAsset(JsonObject json, String key, String field, PackageArchive archive, String prefix)
            throws IOException {
        return json.has(key) ? asset(archive, prefix, string(json.get(key), field), field) : null;
    }

    private static PackageAsset asset(PackageArchive archive, String prefix, String reference, String field) throws IOException {
        try {
            return archive.resolve(prefix, reference);
        } catch (IOException exception) {
            throw new IOException(field + ": " + exception.getMessage(), exception);
        }
    }

    private static String optionalString(JsonObject json, String key) throws IOException {
        return json.has(key) ? string(json.get(key), key) : "";
    }

    private static String string(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException(field + " must be a string");
        }
        return value.getAsString();
    }

    private static JsonPrimitive number(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException(field + " must be a number");
        }
        return value.getAsJsonPrimitive();
    }

    private static JsonObject object(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException(field + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonArray()) throw new IOException(field + " must be an array");
        return value.getAsJsonArray();
    }

}
