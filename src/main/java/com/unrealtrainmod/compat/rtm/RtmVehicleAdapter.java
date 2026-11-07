/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.MaterialRenderMode;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.model.ModelTexture;
import com.unrealtrainmod.model.MqoModelInput;
import com.unrealtrainmod.model.MqoParser;
import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.resource.cache.ModelCache;
import com.unrealtrainmod.resource.cache.ModelCacheCodecs;
import com.unrealtrainmod.vehicle.VehicleAction;
import com.unrealtrainmod.vehicle.VehicleActions;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.vehicle.VehicleInteriorSpec;
import com.unrealtrainmod.vehicle.VehicleMotionSpec;
import com.unrealtrainmod.vehicle.VehiclePerformanceSpec;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converts RTM vehicle configurations without publishing catalogs. */
final class RtmVehicleAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtmVehicleAdapter.class);
    private final Map<ResourceLocation, VehicleDefinition> definitions;
    private final Map<PackageAsset, ModelBounds> boundsCache = new HashMap<>();
    private final LoadReport report;
    private final ModelCache cache;

    RtmVehicleAdapter(Map<ResourceLocation, VehicleDefinition> definitions, LoadReport report, ModelCache cache) {
        this.definitions = definitions;
        this.report = report;
        this.cache = cache;
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
            var actions = readActions(json, name);
            var parts = new ArrayList<VehicleDefinition.Instance>();
            parts.add(readPart(readSource(json.getAsJsonObject("trainModel2"), archive,
                    prefix, smoothing, cullFaces), new ModelData.Position(0, 0, 0), VehiclePartRole.BODY, archive,
                    actions));
            List<ModelData.Position> bogiePositions;
            try {
                bogiePositions = readBogiePositions(json);
            } catch (IOException | RuntimeException exception) {
                throw new IOException(name + " bogiePos: " + exception.getMessage(), exception);
            }
            if (json.has("bogieModel3")) {
                var configured = json.get("bogieModel3");
                if (!configured.isJsonArray() || configured.getAsJsonArray().size() != 2) {
                    throw configError(name, "bogieModel3", "must contain two model objects");
                }
                if (bogiePositions.isEmpty()) throw configError(name, "bogieModel3", "requires bogiePos");
                var bogies = configured.getAsJsonArray();
                float frontZ = Math.max(bogiePositions.get(0).z(), bogiePositions.get(1).z());
                for (int i = 0; i < bogies.size(); i++) {
                    String field = "bogieModel3[" + i + "]";
                    if (!bogies.get(i).isJsonObject()) throw configError(name, field, "must be a model object");
                    var position = bogiePositions.get(i);
                    try {
                        var bogie = readSource(bogies.get(i).getAsJsonObject(), archive, prefix, smoothing, cullFaces);
                        parts.add(readPart(bogie, position, position.z() == frontZ
                                ? VehiclePartRole.FRONT_BOGIE : VehiclePartRole.REAR_BOGIE, archive,
                                VehicleActions.NONE));
                    } catch (IOException | RuntimeException exception) {
                        throw new IOException(name + " " + field + ": " + exception.getMessage(), exception);
                    }
                }
            } else if (json.has("bogieModel2")) {
                var bogie = readSource(json.getAsJsonObject("bogieModel2"), archive, prefix, smoothing, cullFaces);
                if (bogiePositions.isEmpty()) throw new IOException("bogieModel2 requires bogiePos");
                float frontZ = Math.max(bogiePositions.get(0).z(), bogiePositions.get(1).z());
                for (var position : bogiePositions) {
                    parts.add(readPart(bogie, position, position.z() == frontZ
                            ? VehiclePartRole.FRONT_BOGIE : VehiclePartRole.REAR_BOGIE, archive,
                            VehicleActions.NONE));
                }
            }
            var combined = new ModelBounds.Builder();
            for (var part : parts) {
                var partBounds = part.bounds();
                if (part.reversedBogie()) {
                    partBounds = new ModelBounds(-partBounds.maxX(), partBounds.minY(), -partBounds.maxZ(),
                            -partBounds.minX(), partBounds.maxY(), -partBounds.minZ());
                }
                combined.include(partBounds.translated(part.offset()));
            }
            var bounds = combined.build();
            var definition = new VehicleDefinition(id, name, config, RtmButtonTexture.read(json, archive, prefix, config),
                    GsonHelper.getAsString(json, "trainType", ""), parts, bounds,
                    readMotionSpec(json, bounds, bogiePositions), VehiclePerformanceSpec.DEFAULT,
                    readInteriorSpec(json, bounds, name), Map.of());
            definitions.put(id, definition);
            report.registered(LoadReport.Kind.RTM_VEHICLE);
        } catch (IOException | RuntimeException exception) {
            report.failed(LoadReport.Kind.RTM_VEHICLE, config, exception);
        }
    }

    private VehicleDefinition.Instance readPart(ModelSource source, ModelData.Position offset,
                                                VehiclePartRole role, PackageArchive archive,
                                                VehicleActions actions) throws IOException {
        var asset = source.model();
        var bounds = boundsCache.get(asset);
        if (bounds == null) {
            bounds = cache.load(asset, "bounds", "rtm-vehicle:" + ModelSource.MQO_SCALE, ModelCacheCodecs.BOUNDS, () -> {
                try (var input = MqoModelInput.open(archive.open(asset.entry()), asset.entry())) {
                    return MqoParser.scanBounds(input, StandardCharsets.UTF_8, ModelSource.MQO_SCALE);
                }
            });
            boundsCache.put(asset, bounds);
        }
        return new VehicleDefinition.Instance(source, offset, role, new TrackVector(0, 0, 0), bounds, actions);
    }

    /**
     * RTM stores door actions as {@code VehicleParts[]}: object names, a pivot, transforms and nested child
     * parts. A missing field leaves the model static; a present but malformed node rejects the vehicle config.
     */
    private static VehicleActions readActions(JsonObject json, String trainName) throws IOException {
        return new VehicleActions(readActionList(json, "door_left", trainName),
                readActionList(json, "door_right", trainName));
    }

    private static List<VehicleAction> readActionList(JsonObject json, String field, String trainName)
            throws IOException {
        if (!json.has(field) || json.get(field).isJsonNull()) return List.of();
        return readActionArray(json.get(field), field, trainName);
    }

    private static List<VehicleAction> readActionArray(JsonElement element, String path,
                                                       String trainName) throws IOException {
        if (!element.isJsonArray()) throw configError(trainName, path, "must be an array");
        var array = element.getAsJsonArray();
        var result = new ArrayList<VehicleAction>(array.size());
        for (int i = 0; i < array.size(); i++) {
            result.add(readAction(array.get(i), path + "[" + i + "]", trainName));
        }
        return List.copyOf(result);
    }

    private static VehicleAction readAction(JsonElement element, String path, String trainName)
            throws IOException {
        if (!element.isJsonObject()) throw configError(trainName, path, "must be an object");
        var json = element.getAsJsonObject();
        var objectsElement = json.get("objects");
        if (objectsElement == null || !objectsElement.isJsonArray()) {
            throw configError(trainName, path + ".objects", "must be an array of object names");
        }
        var objects = new ArrayList<String>();
        for (var object : objectsElement.getAsJsonArray()) {
            if (!object.isJsonPrimitive() || !object.getAsJsonPrimitive().isString()) {
                throw configError(trainName, path + ".objects", "entries must be strings");
            }
            String name = object.getAsString().trim();
            if (name.isEmpty()) throw configError(trainName, path + ".objects", "names must not be blank");
            objects.add(name);
        }

        var pivot = readPivot(json, path, trainName);
        var transforms = new ArrayList<VehicleAction.Transform>();
        if (json.has("transform") && !json.get("transform").isJsonNull()) {
            var transformElement = json.get("transform");
            if (!transformElement.isJsonArray()) {
                throw configError(trainName, path + ".transform", "must be an array");
            }
            var transformArray = transformElement.getAsJsonArray();
            for (int i = 0; i < transformArray.size(); i++) {
                transforms.add(readTransform(transformArray.get(i), path + ".transform[" + i + "]", trainName));
            }
        }

        var children = List.<VehicleAction>of();
        if (json.has("childParts") && !json.get("childParts").isJsonNull()) {
            children = readActionArray(json.get("childParts"), path + ".childParts", trainName);
        }
        return new VehicleAction(objects, pivot, transforms, children);
    }

    private static TrackVector readPivot(JsonObject json, String path, String trainName) throws IOException {
        if (!json.has("pos") || json.get("pos").isJsonNull()) return new TrackVector(0, 0, 0);
        var element = json.get("pos");
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw configError(trainName, path + ".pos", "must contain three finite XYZ values");
        }
        var values = element.getAsJsonArray();
        double x = readFinite(values.get(0), trainName, path + ".pos[0]");
        double y = readFinite(values.get(1), trainName, path + ".pos[1]");
        double z = readFinite(values.get(2), trainName, path + ".pos[2]");
        return new TrackVector(x, y, z);
    }

    private static VehicleAction.Transform readTransform(JsonElement element, String path,
                                                         String trainName) throws IOException {
        if (!element.isJsonArray()) throw configError(trainName, path, "must be an array");
        var values = element.getAsJsonArray();
        if (values.size() != 3 && values.size() != 4) {
            throw configError(trainName, path, "must contain XYZ translation or angle/axis rotation");
        }
        if (values.size() == 3) {
            return new VehicleAction.Translate(
                    (float)readFinite(values.get(0), trainName, path + "[0]"),
                    (float)readFinite(values.get(1), trainName, path + "[1]"),
                    (float)readFinite(values.get(2), trainName, path + "[2]"));
        }
        float angle = (float)readFinite(values.get(0), trainName, path + "[0]");
        float x = (float)readFinite(values.get(1), trainName, path + "[1]");
        float y = (float)readFinite(values.get(2), trainName, path + "[2]");
        float z = (float)readFinite(values.get(3), trainName, path + "[3]");
        if ((double)x * x + (double)y * y + (double)z * z < 1e-12) {
            throw configError(trainName, path, "rotation axis must not be zero");
        }
        return new VehicleAction.Rotate(angle, x, y, z);
    }

    private static double readFinite(JsonElement element, String trainName, String path)
            throws IOException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw configError(trainName, path, "must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) throw configError(trainName, path, "must be finite");
        if (!Float.isFinite((float)value)) {
            throw configError(trainName, path, "must be within the finite float range");
        }
        return value;
    }

    private static IOException configError(String trainName, String path, String message) {
        return new IOException(trainName + " " + path + ": " + message);
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

    /**
     * Explicit {@code walkable} data wins. Without it the provisional calibration uses the authored
     * bounds inset by {@link VehicleInteriorSpec#BOUNDS_INSET} and the RTM floor at local Y 0.
     */
    private static VehicleInteriorSpec readInteriorSpec(JsonObject json, ModelBounds bounds, String trainName)
            throws IOException {
        if (!json.has("walkable") || json.get("walkable").isJsonNull()) {
            return VehicleInteriorSpec.fromBounds(bounds, rtmFloor(json, trainName, bounds.minY()));
        }
        var walkable = json.get("walkable");
        if (!walkable.isJsonObject()) throw configError(trainName, "walkable", "must be an object");
        var object = walkable.getAsJsonObject();
        if (!GsonHelper.getAsBoolean(object, "enabled", true)) return VehicleInteriorSpec.DISABLED;
        var centerElement = object.get("center");
        if (centerElement == null || !centerElement.isJsonArray() || centerElement.getAsJsonArray().size() != 3) {
            throw configError(trainName, "walkable.center", "must contain three finite XYZ values");
        }
        var center = centerElement.getAsJsonArray();
        double x = readFinite(center.get(0), trainName, "walkable.center[0]");
        double y = readFinite(center.get(1), trainName, "walkable.center[1]");
        double z = readFinite(center.get(2), trainName, "walkable.center[2]");
        double length = readFinite(object.get("length"), trainName, "walkable.length");
        double width = readFinite(object.get("width"), trainName, "walkable.width");
        try {
            return new VehicleInteriorSpec(true, x, y, z, length, width);
        } catch (IllegalArgumentException exception) {
            throw new IOException(trainName + " walkable: " + exception.getMessage(), exception);
        }
    }

    private static double rtmFloor(JsonObject json, String trainName, double fallback) throws IOException {
        double floor = lowestY(json, "slotPos", trainName, false);
        if (Double.isFinite(floor)) return floor;
        floor = lowestY(json, "seatPos", trainName, true);
        if (Double.isFinite(floor)) return floor;
        floor = lowestY(json, "playerPos", trainName, false);
        return Double.isFinite(floor) ? floor : fallback;
    }

    private static double lowestY(JsonObject json, String field, String trainName, boolean sixteenth) throws IOException {
        if (!json.has(field) || !json.get(field).isJsonArray()) return Double.NaN;
        double lowest = Double.POSITIVE_INFINITY;
        for (var element : json.getAsJsonArray(field)) {
            if (!element.isJsonArray() || element.getAsJsonArray().size() < 2) {
                throw configError(trainName, field, "entries must contain at least X and Y");
            }
            double y = readFinite(element.getAsJsonArray().get(1), trainName, field + "[].y");
            if (sixteenth) y /= 16.0;
            lowest = Math.min(lowest, y);
        }
        return lowest == Double.POSITIVE_INFINITY ? Double.NaN : lowest;
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
        if (!MqoModelInput.isSupported(model)) {
            throw new IOException("Only text MQO or MQOZ models are supported: " + model);
        }
        var asset = archive.resolve(prefix, "minecraft:models/" + model);
        PackageAsset renderer = null;
        if (json.has("rendererPath")) {
            String path = GsonHelper.getAsString(json, "rendererPath");
            if (path.isBlank()) throw new IOException("rendererPath must not be blank");
            renderer = archive.resolve(prefix, "minecraft:" + path);
        }
        var textures = new HashMap<String, ModelTexture>();
        for (var element : GsonHelper.getAsJsonArray(json, "textures")) {
            var texture = element.getAsJsonArray();
            if (texture.size() < 2) throw new IOException("Expected material name and texture path");
            String material = texture.get(0).getAsString();
            var image = archive.resolve(prefix, texture.get(1).getAsString());
            var flags = texture.size() > 2 ? Arrays.stream(texture.get(2).getAsString().split(","))
                    .map(String::strip).toList() : List.<String>of();
            ModelTexture.Lights lights = null;
            if (flags.contains("Light")) {
                if (texture.size() > 6) throw new IOException(model + ": at most three light textures are supported");
                var layers = new PackageAsset[3];
                for (int layer = 0; layer < layers.length; layer++) {
                    if (texture.size() > 3) {
                        if (layer + 3 < texture.size()) {
                            layers[layer] = archive.resolve(prefix, texture.get(layer + 3).getAsString());
                        }
                    } else if (flags.contains("OneTex")) {
                        layers[layer] = image;
                    } else {
                        String path = texture.get(1).getAsString();
                        int dot = path.lastIndexOf('.');
                        String lightPath = (dot <= path.lastIndexOf('/') ? path : path.substring(0, dot))
                                + "_light" + layer + ".png";
                        try {
                            layers[layer] = archive.resolve(prefix, lightPath);
                        } catch (IOException exception) {
                            LOGGER.warn("RTM {} material '{}': ignoring missing light layer {}: {}",
                                    asset, material, layer, lightPath);
                        }
                    }
                }
                lights = new ModelTexture.Lights(layers[0], layers[1], layers[2]);
            }
            textures.put(material, new ModelTexture(image, flags.contains("AlphaBlend"), lights));
        }
        return new ModelSource(asset, textures, readMaterialRenderModes(json, model), smoothing, cullFaces,
                ModelSource.Format.MQO, ModelSource.MQO_SCALE, renderer);
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
