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
import com.unrealtrainmod.vehicle.VehicleMotionSpec;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import com.unrealtrainmod.track.geometry.TrackVector;
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
            parts.add(readPart(readSource(json.getAsJsonObject("trainModel2"), archive,
                    prefix, smoothing, cullFaces), new ModelData.Position(0, 0, 0), VehiclePartRole.BODY, archive));
            var bogiePositions = readBogiePositions(json);
            if (json.has("bogieModel2")) {
                var bogie = readSource(json.getAsJsonObject("bogieModel2"), archive, prefix, smoothing, cullFaces);
                if (bogiePositions.isEmpty()) throw new IOException("bogieModel2 requires bogiePos");
                float frontZ = Math.max(bogiePositions.get(0).z(), bogiePositions.get(1).z());
                for (var position : bogiePositions) {
                    parts.add(readPart(bogie, position, position.z() == frontZ
                            ? VehiclePartRole.FRONT_BOGIE : VehiclePartRole.REAR_BOGIE, archive));
                }
            }
            var combined = new ModelBounds.Builder();
            for (var part : parts) {
                combined.include(part.bounds().translated(part.offset()));
            }
            var bounds = combined.build();
            var definition = new VehicleDefinition(id, name, config, RtmButtonTexture.read(json, archive, prefix, config),
                    GsonHelper.getAsString(json, "trainType", ""), parts, bounds,
                    readMotionSpec(json, bounds, bogiePositions));
            definitions.put(id, definition);
            report.registered(LoadReport.Kind.RTM_VEHICLE);
        } catch (IOException | RuntimeException exception) {
            report.failed(LoadReport.Kind.RTM_VEHICLE, config, exception);
        }
    }

    private VehicleDefinition.Instance readPart(ModelSource source, ModelData.Position offset,
                                                VehiclePartRole role, PackageArchive archive) throws IOException {
        var asset = source.model();
        var bounds = boundsCache.get(asset);
        if (bounds == null) {
            try (var input = archive.open(asset.entry())) {
                bounds = MqoParser.scanBounds(input, StandardCharsets.UTF_8, ModelSource.MQO_SCALE);
            }
            boundsCache.put(asset, bounds);
        }
        return new VehicleDefinition.Instance(source, offset, role, new TrackVector(0, 0, 0), bounds);
    }

    private static List<ModelData.Position> readBogiePositions(JsonObject json) throws IOException {
        if (!json.has("bogiePos")) return List.of();
        var positions = GsonHelper.getAsJsonArray(json, "bogiePos");
        if (positions.size() != 2) throw new IOException("bogiePos must contain two XYZ positions");
        var result = new ArrayList<ModelData.Position>(2);
        for (var position : positions) {
            var xyz = position.getAsJsonArray();
            if (xyz.size() != 3) throw new IOException("Expected XYZ bogie position");
            float x = xyz.get(0).getAsFloat(), y = xyz.get(1).getAsFloat(), z = xyz.get(2).getAsFloat();
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IOException("Non-finite bogiePos coordinate");
            }
            result.add(new ModelData.Position(x, y, z));
        }
        return result;
    }

    private static VehicleMotionSpec readMotionSpec(JsonObject json, ModelBounds bounds,
                                                    List<ModelData.Position> bogiePositions) throws IOException {
        var base = VehicleMotionSpec.fromBounds(bounds, 1);
        double halfLength = GsonHelper.getAsDouble(json, "trainDistance", 0);
        if (!Double.isFinite(halfLength)) throw new IOException("trainDistance must be finite");
        double frontBogie = base.frontBogieOffset(), rearBogie = base.rearBogieOffset();
        if (!bogiePositions.isEmpty()) {
            double first = bogiePositions.get(0).z(), second = bogiePositions.get(1).z();
            frontBogie = Math.max(first, second);
            rearBogie = Math.min(first, second);
        }
        return new VehicleMotionSpec(halfLength > 0 ? 2 * halfLength : base.length(), base.width(), base.height(),
                base.trackDatum(), halfLength > 0 ? halfLength : base.frontCouplerOffset(),
                halfLength > 0 ? -halfLength : base.rearCouplerOffset(), frontBogie, rearBogie, 0);
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
