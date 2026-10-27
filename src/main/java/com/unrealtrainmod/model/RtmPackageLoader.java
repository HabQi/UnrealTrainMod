/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import com.google.gson.JsonObject;
import com.unrealtrainmod.UnrealTrainMod;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

/** Independent reader for the static MQO subset of the RTM package format. */
public final class RtmPackageLoader {
    private static final String TRAIN_DIRECTORY = "mods/RTM/train/";
    private static final Pattern CONFIG = Pattern.compile("^(?:[^/]+/)?mods/RTM/train/ModelTrain[^/]*\\.json$");

    private RtmPackageLoader() {}

    public static void load(Path modsDirectory) {
        var definitions = new LinkedHashMap<ResourceLocation, TrainModels.Definition>();
        var boundsCache = new HashMap<RtmAsset, ModelBounds>();
        Path directory = modsDirectory.resolve("Packages_RTM");
        try {
            Files.createDirectories(modsDirectory.resolve("Packages_IR"));
            Files.createDirectories(directory);
            List<Path> archives;
            try (var files = Files.list(directory)) {
                archives = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            }
            for (var archive : archives) loadArchive(archive, definitions, boundsCache);
        } catch (IOException e) {
            UnrealTrainMod.LOGGER.error("Unable to initialize package directories under {}", modsDirectory, e);
        }
        TrainModels.register(new ArrayList<>(definitions.values()));
        UnrealTrainMod.LOGGER.info("Registered {} RTM vehicles from {}", definitions.size(), directory);
    }

    private static void loadArchive(Path archive, Map<ResourceLocation, TrainModels.Definition> definitions,
                                    Map<RtmAsset, ModelBounds> boundsCache) {
        try (var zip = new ZipFile(archive.toFile())) {
            var configs = zip.stream().filter(entry -> !entry.isDirectory() && CONFIG.matcher(entry.getName()).matches())
                    .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            for (var entry : configs) {
                try {
                    JsonObject json;
                    try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                        json = GsonHelper.parse(reader);
                    }
                    String name = GsonHelper.getAsString(json, "trainName");
                    if (name.isBlank()) throw new IOException("trainName must not be blank");
                    var id = TrainModels.idForName(name);
                    if (definitions.containsKey(id)) {
                        UnrealTrainMod.LOGGER.warn("Duplicate RTM trainName '{}': skipping {}!{}; retained package {}",
                                name, archive, entry.getName(), definitions.get(id).packName());
                        continue;
                    }
                    String prefix = entry.getName().substring(0, entry.getName().indexOf(TRAIN_DIRECTORY));
                    boolean smoothing = GsonHelper.getAsBoolean(json, "smoothing", false);
                    boolean cullFaces = GsonHelper.getAsBoolean(json, "doCulling", false);
                    var parts = new ArrayList<TrainModels.Instance>();
                    parts.add(new TrainModels.Instance(readSource(json.getAsJsonObject("trainModel2"), archive, zip,
                            prefix, smoothing, cullFaces), new ModelData.Position(0, 0, 0)));
                    if (json.has("bogieModel2")) {
                        var bogie = readSource(json.getAsJsonObject("bogieModel2"), archive, zip, prefix, smoothing, cullFaces);
                        var positions = GsonHelper.getAsJsonArray(json, "bogiePos");
                        if (positions.size() != 2) throw new IOException("bogiePos must contain two XYZ positions");
                        for (var position : positions) {
                            var xyz = position.getAsJsonArray();
                            if (xyz.size() != 3) throw new IOException("Expected XYZ bogie position");
                            float x = xyz.get(0).getAsFloat(), y = xyz.get(1).getAsFloat(), z = xyz.get(2).getAsFloat();
                            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                                throw new IOException("Non-finite bogie position");
                            }
                            parts.add(new TrainModels.Instance(bogie, new ModelData.Position(x, y, z)));
                        }
                    }
                    var combined = new ModelBounds.Builder();
                    for (var part : parts) {
                        var source = part.source().model();
                        var bounds = boundsCache.get(source);
                        if (bounds == null) {
                            try (var input = source.open()) {
                                bounds = MqoParser.scanBounds(input, StandardCharsets.UTF_8);
                            }
                            boundsCache.put(source, bounds);
                        }
                        combined.include(bounds.translated(part.offset()));
                    }
                    var definition = new TrainModels.Definition(id, name, archive.getFileName().toString(),
                            GsonHelper.getAsString(json, "trainType", ""), parts, combined.build());
                    definitions.put(id, definition);
                } catch (IOException | RuntimeException e) {
                    UnrealTrainMod.LOGGER.error("Unable to register RTM vehicle {}!{}", archive, entry.getName(), e);
                }
            }
        } catch (IOException | RuntimeException e) {
            UnrealTrainMod.LOGGER.error("Unable to read RTM package {}", archive, e);
        }
    }

    private static TrainModels.Source readSource(JsonObject json, Path archive, ZipFile zip, String prefix,
                                                  boolean smoothing, boolean cullFaces) throws IOException {
        if (json == null) throw new IOException("Missing model source");
        String model = GsonHelper.getAsString(json, "modelFile");
        if (!model.toLowerCase(Locale.ROOT).endsWith(".mqo")) throw new IOException("Only text MQO models are supported: " + model);
        var asset = requireAsset(archive, zip, prefix, "minecraft:models/" + model);
        var textures = new HashMap<String, TrainModels.Texture>();
        for (var element : GsonHelper.getAsJsonArray(json, "textures")) {
            var texture = element.getAsJsonArray();
            if (texture.size() < 2) throw new IOException("Expected material name and texture path");
            String material = texture.get(0).getAsString();
            var image = requireAsset(archive, zip, prefix, texture.get(1).getAsString());
            boolean alphaBlend = texture.size() > 2 && List.of(texture.get(2).getAsString().split(","))
                    .stream().anyMatch(flag -> flag.strip().equals("AlphaBlend"));
            textures.put(material, new TrainModels.Texture(image, alphaBlend));
        }
        return new TrainModels.Source(asset, textures, smoothing, cullFaces);
    }

    private static RtmAsset requireAsset(Path archive, ZipFile zip, String prefix, String reference) throws IOException {
        String normalized = reference.replace('\\', '/');
        int colon = normalized.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : normalized.substring(0, colon);
        String path = colon < 0 ? normalized : normalized.substring(colon + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || path.isEmpty() || path.startsWith("/") || path.indexOf(':') >= 0
                || List.of(path.split("/", -1)).stream().anyMatch(segment -> segment.isEmpty() || segment.equals("..") || segment.equals("."))) {
            throw new IOException("Invalid package resource path: " + reference);
        }
        String entry = prefix + "assets/" + namespace + "/" + path;
        var file = zip.getEntry(entry);
        if (file == null || file.isDirectory()) throw new IOException("Missing resource: " + entry);
        return new RtmAsset(archive, entry);
    }
}
