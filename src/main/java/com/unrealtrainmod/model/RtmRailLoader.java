/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import static com.unrealtrainmod.model.RtmResources.requireAsset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.unrealtrainmod.UnrealTrainMod;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/** One startup scan. Configurations share raw model data, never vehicle instances or rendering resources. */
final class RtmRailLoader {
    private static final String DIRECTORY = "mods/RTM/rail/";
    private static final Pattern CONFIG = Pattern.compile("^(?:[^/]+/)?mods/RTM/rail/ModelRail[^/]*\\.json$");
    private final Map<ResourceLocation, RailModels.Definition> definitions = new LinkedHashMap<>();
    private final Map<RtmAsset, ModelData> models = new HashMap<>();
    private final Map<RtmAsset, IOException> failedModels = new HashMap<>();
    private int failures;
    private int duplicates;
    private int failedArchives;

    void loadArchive(Path archive, ZipFile zip) {
        var configs = zip.stream().filter(entry -> !entry.isDirectory() && CONFIG.matcher(entry.getName()).matches())
                .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
        for (var entry : configs) {
            try {
                JsonObject json;
                try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    json = GsonHelper.parse(reader);
                }
                String name = string(json.get("railName"), "railName");
                if (name.isBlank()) throw new IOException("railName must not be blank");
                var id = RailModels.idForName(name);
                RailModels.Definition retained = definitions.get(id);
                if (retained != null) {
                    duplicates++;
                    UnrealTrainMod.LOGGER.warn("Duplicate RTM railName '{}': skipping {}!{}; retained {}!{}",
                            name, archive, entry.getName(), retained.packName(), retained.configPath());
                    continue;
                }
                String prefix = entry.getName().substring(0, entry.getName().indexOf(DIRECTORY));
                JsonObject model = object(json.get("model"), "model");
                String modelFile = string(model.get("modelFile"), "model.modelFile");
                if (!modelFile.toLowerCase(Locale.ROOT).endsWith(".mqo")) {
                    throw new IOException("model.modelFile: only text MQO models are supported: " + modelFile);
                }
                RtmAsset asset = asset(archive, zip, prefix, "minecraft:models/" + modelFile, "model.modelFile");
                Map<String, RailModels.Texture> textures = textures(model, archive, zip, prefix);
                RtmAsset renderer = optionalAsset(model, "rendererPath", "model.rendererPath", archive, zip, prefix);
                RtmAsset button = optionalAsset(json, "buttonTexture", "buttonTexture", archive, zip, prefix);
                String accuracy = optionalString(json, "accuracy"), tags = optionalString(json, "tags");
                List<RailModels.Ballast> ballast = ballast(json);
                // Read geometry last so invalid metadata cannot retain an otherwise unused model in the cache.
                ModelData data = modelData(asset, zip);
                for (String material : textures.keySet()) {
                    if (data.materials().stream().noneMatch(value -> value.name().equals(material))) {
                        UnrealTrainMod.LOGGER.warn("RTM rail {}!{}: model.textures material '{}' matches no MQO material in {}",
                                archive, entry.getName(), material, asset.entry());
                    }
                }
                var source = new RailModels.Source(asset, data, textures, renderer);
                definitions.put(id, new RailModels.Definition(id, name, archive.getFileName().toString(), entry.getName(),
                        source, button, accuracy, tags, ballast));
            } catch (IOException | RuntimeException exception) {
                failures++;
                UnrealTrainMod.LOGGER.error("Unable to register RTM rail {}!{}: {}",
                        archive, entry.getName(), exception.getMessage(), exception);
            }
        }
    }

    private ModelData modelData(RtmAsset asset, ZipFile zip) throws IOException {
        ModelData data = models.get(asset);
        if (data != null) return data;
        IOException previous = failedModels.get(asset);
        if (previous != null) throw new IOException("model.modelFile: previous MQO parsing failed for " + asset.entry(), previous);
        try (var input = zip.getInputStream(zip.getEntry(asset.entry()))) {
            data = MqoParser.parse(input, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            var failure = new IOException("model.modelFile " + asset.entry() + ": " + exception.getMessage(), exception);
            failedModels.put(asset, failure);
            throw failure;
        }
        models.put(asset, data);
        return data;
    }

    private static Map<String, RailModels.Texture> textures(JsonObject model, Path archive, ZipFile zip, String prefix)
            throws IOException {
        if (!model.has("textures")) return Map.of();
        JsonArray configured = array(model.get("textures"), "model.textures");
        var textures = new LinkedHashMap<String, RailModels.Texture>();
        for (int i = 0; i < configured.size(); i++) {
            String field = "model.textures[" + i + "]";
            JsonArray binding = array(configured.get(i), field);
            if (binding.size() < 2 || binding.size() > 3) {
                throw new IOException(field + " must contain a material name, texture path and optional flags string");
            }
            String material = string(binding.get(0), field + "[0]");
            if (material.isBlank()) throw new IOException(field + "[0]: material name must not be blank");
            RtmAsset image = asset(archive, zip, prefix, string(binding.get(1), field + "[1]"), field + "[1]");
            List<String> flags = binding.size() == 2 ? List.of()
                    : Arrays.stream(string(binding.get(2), field + "[2]").split(",", -1))
                            .map(String::strip).filter(flag -> !flag.isEmpty()).toList();
            if (textures.putIfAbsent(material, new RailModels.Texture(image, flags)) != null) {
                throw new IOException(field + ": duplicate material binding '" + material + "'");
            }
        }
        return textures;
    }

    private static List<RailModels.Ballast> ballast(JsonObject json) throws IOException {
        if (!json.has("defaultBallast")) return List.of();
        JsonArray configured = array(json.get("defaultBallast"), "defaultBallast");
        var result = new ArrayList<RailModels.Ballast>();
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
            result.add(new RailModels.Ballast(blockName, metadata, height));
        }
        return result;
    }

    private static RtmAsset optionalAsset(JsonObject json, String key, String field, Path archive, ZipFile zip, String prefix)
            throws IOException {
        return json.has(key) ? asset(archive, zip, prefix, string(json.get(key), field), field) : null;
    }

    private static RtmAsset asset(Path archive, ZipFile zip, String prefix, String reference, String field) throws IOException {
        try {
            return requireAsset(archive, zip, prefix, reference);
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

    void archiveFailed() { failedArchives++; }

    void register(Path directory) {
        RailModels.register(new ArrayList<>(definitions.values()));
        var usage = new HashMap<RtmAsset, Integer>();
        for (var definition : definitions.values()) usage.merge(definition.source().model(), 1, Integer::sum);
        long shared = usage.values().stream().filter(count -> count > 1).count();
        long scripts = definitions.values().stream().filter(definition -> definition.source().rendererPath() != null).count();
        UnrealTrainMod.LOGGER.info("Registered {} RTM rails from {}: {} failed configs, {} duplicates, {} unreadable archives, "
                        + "{} unique MQO models ({} shared, {} reused references), {} declared scripts (not executed)",
                definitions.size(), directory, failures, duplicates, failedArchives,
                usage.size(), shared, definitions.size() - usage.size(), scripts);
    }
}
