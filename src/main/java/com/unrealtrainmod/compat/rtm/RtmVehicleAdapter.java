/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm;

import com.google.gson.JsonObject;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.MaterialRenderMode;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.model.ModelTexture;
import com.unrealtrainmod.model.MqoParser;
import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/** Converts RTM vehicle configurations without publishing catalogs. */
final class RtmVehicleAdapter {
    private final Map<ResourceLocation, VehicleDefinition> definitions;
    private final Map<PackageAsset, ModelBounds> boundsCache = new HashMap<>();
    private final LoadReport report;

    RtmVehicleAdapter(Map<ResourceLocation, VehicleDefinition> definitions, LoadReport report) {
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
            String name = GsonHelper.getAsString(json, "trainName");
            if (name.isBlank()) throw new IOException("trainName must not be blank");
            var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "rtm/" + ResourceIds.hash(name));
            var retained = definitions.get(id);
            if (retained != null) {
                report.duplicate(LoadReport.Kind.RTM_VEHICLE, id, config, retained.config());
                return;
            }
            boolean smoothing = GsonHelper.getAsBoolean(json, "smoothing", false);
            boolean cullFaces = GsonHelper.getAsBoolean(json, "doCulling", false);
            var parts = new ArrayList<VehicleDefinition.Instance>();
            parts.add(new VehicleDefinition.Instance(readSource(json.getAsJsonObject("trainModel2"), archive,
                    prefix, smoothing, cullFaces), new ModelData.Position(0, 0, 0)));
            if (json.has("bogieModel2")) {
                var bogie = readSource(json.getAsJsonObject("bogieModel2"), archive, prefix, smoothing, cullFaces);
                var positions = GsonHelper.getAsJsonArray(json, "bogiePos");
                if (positions.size() != 2) throw new IOException("bogiePos must contain two XYZ positions");
                for (var position : positions) {
                    var xyz = position.getAsJsonArray();
                    if (xyz.size() != 3) throw new IOException("Expected XYZ bogie position");
                    float x = xyz.get(0).getAsFloat(), y = xyz.get(1).getAsFloat(), z = xyz.get(2).getAsFloat();
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                        throw new IOException("Non-finite bogie position");
                    }
                    parts.add(new VehicleDefinition.Instance(bogie, new ModelData.Position(x, y, z)));
                }
            }
            var combined = new ModelBounds.Builder();
            for (var part : parts) {
                var source = part.source().model();
                var bounds = boundsCache.get(source);
                if (bounds == null) {
                    try (var input = archive.open(source.entry())) {
                        bounds = MqoParser.scanBounds(input, StandardCharsets.UTF_8, ModelSource.MQO_SCALE);
                    }
                    boundsCache.put(source, bounds);
                }
                combined.include(bounds.translated(part.offset()));
            }
            var definition = new VehicleDefinition(id, name, config,
                    GsonHelper.getAsString(json, "trainType", ""), parts, combined.build());
            definitions.put(id, definition);
            report.registered(LoadReport.Kind.RTM_VEHICLE);
        } catch (IOException | RuntimeException exception) {
            report.failed(LoadReport.Kind.RTM_VEHICLE, config, exception);
        }
    }

    private static ModelSource readSource(JsonObject json, PackageArchive archive, String prefix,
                                                  boolean smoothing, boolean cullFaces) throws IOException {
        if (json == null) throw new IOException("Missing model source");
        String model = GsonHelper.getAsString(json, "modelFile");
        if (!model.toLowerCase(Locale.ROOT).endsWith(".mqo")) throw new IOException("Only text MQO models are supported: " + model);
        var asset = archive.resolve(prefix, "minecraft:models/" + model);
        var textures = new HashMap<String, ModelTexture>();
        for (var element : GsonHelper.getAsJsonArray(json, "textures")) {
            var texture = element.getAsJsonArray();
            if (texture.size() < 2) throw new IOException("Expected material name and texture path");
            String material = texture.get(0).getAsString();
            var image = archive.resolve(prefix, texture.get(1).getAsString());
            boolean alphaBlend = texture.size() > 2 && List.of(texture.get(2).getAsString().split(","))
                    .stream().anyMatch(flag -> flag.strip().equals("AlphaBlend"));
            textures.put(material, new ModelTexture(image, alphaBlend));
        }
        return new ModelSource(asset, textures, readMaterialRenderModes(json, model), smoothing, cullFaces);
    }

    private static Map<String, MaterialRenderMode> readMaterialRenderModes(JsonObject json, String model) throws IOException {
        if (!json.has("materialRenderModes")) return Map.of();
        var configured = json.get("materialRenderModes");
        if (!configured.isJsonObject()) {
            throw new IOException(model + ": materialRenderModes must be an object keyed by MQO material name");
        }
        var modes = new HashMap<String, MaterialRenderMode>();
        for (var entry : configured.getAsJsonObject().entrySet()) {
            String field = model + ": materialRenderModes['" + entry.getKey() + "']";
            var value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IOException(field + " must be a string: OPAQUE, CUTOUT, TRANSLUCENT or AUTO");
            }
            try {
                modes.put(entry.getKey(), MaterialRenderMode.valueOf(value.getAsString()));
            } catch (IllegalArgumentException e) {
                throw new IOException(field + ": invalid mode '" + value.getAsString()
                        + "'; expected OPAQUE, CUTOUT, TRANSLUCENT or AUTO", e);
            }
        }
        return modes;
    }
}
