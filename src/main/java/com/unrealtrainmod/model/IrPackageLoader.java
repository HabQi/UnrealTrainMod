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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/** Reads the JSON stock catalog and static vehicle definitions inside each IR package. */
public final class IrPackageLoader {
    private static final Pattern CATALOG = Pattern.compile("^([^/]+/)?assets/immersiverailroading/rolling_stock/stock\\.json$");
    private static final List<String> TYPES = List.of("locomotives", "passenger", "freight", "tank", "tender", "hand_car");
    private static final float STANDARD_GAUGE = 1.435f;

    private IrPackageLoader() {}

    public static List<TrainModels.Definition> load(Path modsDirectory) {
        Path directory = modsDirectory.resolve("Packages_IR");
        var definitions = new LinkedHashMap<ResourceLocation, TrainModels.Definition>();
        var bounds = new HashMap<RtmAsset, ModelBounds>();
        try {
            Files.createDirectories(directory);
            List<Path> archives;
            try (var files = Files.list(directory)) {
                archives = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            }
            for (var archive : archives) loadArchive(archive, definitions, bounds);
        } catch (IOException e) {
            UnrealTrainMod.LOGGER.error("Unable to initialize IR package directory {}", directory, e);
        }
        UnrealTrainMod.LOGGER.info("Registered {} IR vehicles from {}", definitions.size(), directory);
        return List.copyOf(definitions.values());
    }

    private static void loadArchive(Path archive, Map<ResourceLocation, TrainModels.Definition> definitions,
                                    Map<RtmAsset, ModelBounds> bounds) {
        try (var zip = new ZipFile(archive.toFile())) {
            var catalogs = zip.stream().filter(entry -> !entry.isDirectory() && CATALOG.matcher(entry.getName()).matches())
                    .sorted(Comparator.comparing(ZipEntry::getName)).toList();
            if (catalogs.isEmpty()) UnrealTrainMod.LOGGER.warn("IR package {} contains no JSON stock catalog", archive);
            for (var entry : catalogs) {
                try {
                    var matcher = CATALOG.matcher(entry.getName());
                    matcher.matches();
                    String prefix = matcher.group(1) == null ? "" : matcher.group(1);
                    JsonObject catalog = readJson(zip, entry);
                    for (String type : TYPES) {
                        if (!catalog.has(type)) continue;
                        if (!catalog.get(type).isJsonArray()) {
                            UnrealTrainMod.LOGGER.error("IR {}!{}: '{}' must be a list of stock names", archive, entry.getName(), type);
                            continue;
                        }
                        for (var stock : catalog.getAsJsonArray(type)) {
                            try {
                                if (!stock.isJsonPrimitive() || !stock.getAsJsonPrimitive().isString()
                                        || stock.getAsString().isBlank()) throw new IOException("Stock name must be a nonblank string");
                                String config = ("immersiverailroading:rolling_stock/" + type + "/" + stock.getAsString() + ".json")
                                        .replace('\\', '/');
                                var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "ir/" + TrainModels.hash(config));
                                var retained = definitions.get(id);
                                if (retained != null) {
                                    UnrealTrainMod.LOGGER.warn("Duplicate IR vehicle {} in {}; retained package {}", config, archive, retained.packName());
                                    continue;
                                }
                                var asset = RtmResources.requireAsset(archive, zip, prefix, config);
                                var definition = readDefinition(id, type, asset, prefix, zip, bounds);
                                definitions.put(id, definition);
                            } catch (IOException | RuntimeException e) {
                                UnrealTrainMod.LOGGER.error("Unable to register IR vehicle {}!{} {} '{}'", archive, entry.getName(), type, stock, e);
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    UnrealTrainMod.LOGGER.error("Unable to read IR stock catalog {}!{}", archive, entry.getName(), e);
                }
            }
        } catch (IOException | RuntimeException e) {
            UnrealTrainMod.LOGGER.error("Unable to read IR package {}", archive, e);
        }
    }

    private static TrainModels.Definition readDefinition(ResourceLocation id, String type, RtmAsset config,
                                                          String prefix, ZipFile zip, Map<RtmAsset, ModelBounds> boundsCache) throws IOException {
        var json = readJson(zip, zip.getEntry(config.entry()));
        String name = GsonHelper.getAsString(json, "name");
        if (name.isBlank()) throw new IOException("name must not be blank");
        String reference = GsonHelper.getAsString(json, "model");
        if (!reference.toLowerCase(Locale.ROOT).endsWith(".obj")) throw new IOException("IR model must be OBJ: " + reference);
        if (reference.indexOf(':') < 0) reference = "immersiverailroading:" + reference;
        var model = RtmResources.requireAsset(config.archive(), zip, prefix, reference);
        float modelGauge = GsonHelper.getAsFloat(json, "model_gauge_m", STANDARD_GAUGE);
        float gauge = GsonHelper.getAsFloat(json, "recommended_gauge_m", modelGauge);
        if (!Float.isFinite(modelGauge) || modelGauge <= 0 || !Float.isFinite(gauge) || gauge <= 0) {
            throw new IOException("model_gauge_m and recommended_gauge_m must be finite and positive");
        }
        float scale = gauge / modelGauge;
        if (!Float.isFinite(scale) || scale <= 0) throw new IOException("Invalid IR model gauge scale");
        var rawBounds = boundsCache.get(model);
        if (rawBounds == null) {
            try (var reader = new InputStreamReader(zip.getInputStream(zip.getEntry(model.entry())), StandardCharsets.UTF_8.newDecoder())) {
                rawBounds = ObjParser.scanBounds(reader, model.entry(), IrModelLoader::isVisible);
            }
            boundsCache.put(model, rawBounds);
        }
        // A proper Y rotation: source +X becomes +Z, source +Z becomes -X. Origin is unchanged.
        var bounds = new ModelBounds(-rawBounds.maxZ() * scale, rawBounds.minY() * scale, rawBounds.minX() * scale,
                -rawBounds.minZ() * scale, rawBounds.maxY() * scale, rawBounds.maxX() * scale);
        var source = new TrainModels.Source(model, Map.of(), Map.of("default", MaterialRenderMode.AUTO),
                false, false, TrainModels.Format.IR_OBJ, scale);
        return new TrainModels.Definition(id, name, config.archive().getFileName().toString(), type,
                List.of(new TrainModels.Instance(source, new ModelData.Position(0, 0, 0))), bounds);
    }

    private static JsonObject readJson(ZipFile zip, ZipEntry entry) throws IOException {
        try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8.newDecoder())) {
            // IR packs may have trailing commas in arrays (the reference YZ25DT animations do).
            return GsonHelper.parse(reader, true);
        }
    }
}
