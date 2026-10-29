/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.track.model.RailDefinition;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Startup-only adapter for named rail entries using the legacy single-OBJ track definition. */
final class IrRailAdapter {
    private record RailModel(IrModelLoader.Imported imported, double top) {}

    private final Map<ResourceLocation, RailDefinition> definitions;
    private final Map<PackageAsset, RailModel> models = new HashMap<>();
    private final Map<PackageAsset, IOException> failedModels = new HashMap<>();
    private final LoadReport report;

    IrRailAdapter(Map<ResourceLocation, RailDefinition> definitions, LoadReport report) {
        this.definitions = definitions;
        this.report = report;
    }

    void read(PackageArchive archive, String prefix, String reference) {
        var config = new PackageAsset(archive.path(), prefix + "assets/" + reference.replace(':', '/'));
        try {
            config = archive.resolve(prefix, reference);
            var json = IrVehicleAdapter.readJson(archive, config.entry());
            String name = string(json.get("name"), "name");
            if (!name.contains("rail") && !name.contains("Rail")) {
                report.filtered(LoadReport.Kind.IR_RAIL);
                return;
            }
            var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "ir/rail/" + ResourceIds.hash(reference));
            var retained = definitions.get(id);
            if (retained != null) {
                report.duplicate(LoadReport.Kind.IR_RAIL, id, config, retained.config());
                return;
            }
            String modelReference = modelReference(json);
            if (!modelReference.toLowerCase(Locale.ROOT).endsWith(".obj")) {
                throw new IOException("IR rail model must be OBJ: " + modelReference);
            }
            if (modelReference.indexOf(':') < 0) modelReference = "immersiverailroading:" + modelReference;
            var asset = archive.resolve(prefix, modelReference);
            double modelGauge = positive(json, "model_gauge_m", RailDefinition.STANDARD_GAUGE);
            double spacing = positive(json, "model_spacing_m", modelGauge / RailDefinition.STANDARD_GAUGE);
            float scale = (float)(RailDefinition.STANDARD_GAUGE / modelGauge);
            double period = spacing * scale;
            if (!Float.isFinite(scale) || scale <= 0 || !Double.isFinite(period) || period <= 0) {
                throw new IOException("Invalid reference-gauge model scale or spacing");
            }
            RailModel model;
            try {
                model = modelData(archive, asset);
            } catch (IOException | RuntimeException exception) {
                report.modelFailed(LoadReport.Kind.IR_RAIL, config, asset, exception);
                return;
            }
            double top = model.top() * scale;
            if (!Double.isFinite(top)) throw new IOException("Invalid reference-gauge rail top");
            var textures = new HashMap<String, RailDefinition.Texture>();
            model.imported().textures().forEach((material, texture) ->
                    textures.put(material, new RailDefinition.Texture(texture.asset(), List.of())));
            var source = new RailDefinition.Source(asset, model.imported().model(), textures, null,
                    RailDefinition.Format.IR_OBJ, scale, top, period);
            definitions.put(id, new RailDefinition(id, name, config, source, null, "", "", List.of()));
            report.registered(LoadReport.Kind.IR_RAIL);
        } catch (IOException | RuntimeException exception) {
            report.failed(LoadReport.Kind.IR_RAIL, config, exception);
        }
    }

    private RailModel modelData(PackageArchive archive, PackageAsset asset) throws IOException {
        var cached = models.get(asset);
        if (cached != null) return cached;
        var previous = failedModels.get(asset);
        if (previous != null) throw new IOException("Previous IR rail model parsing failed: " + asset.entry(), previous);
        try {
            var imported = IrModelLoader.loadRail(archive, asset);
            double top = Double.NEGATIVE_INFINITY;
            boolean left = false, right = false;
            for (var part : imported.model().parts()) {
                boolean isLeft = part.name().contains("RAIL_LEFT"), isRight = part.name().contains("RAIL_RIGHT");
                if ((!isLeft && !isRight) || part.faces().isEmpty()) continue;
                left |= isLeft;
                right |= isRight;
                // Only polygon-referenced rail vertices define the top; attachments and unused vertices do not.
                for (var face : part.faces()) {
                    for (int index : face.indices()) top = Math.max(top, part.positions().get(index).y());
                }
            }
            if (!left || !right || !Double.isFinite(top)) {
                throw new IOException("IR rail model requires polygon geometry in both RAIL_LEFT and RAIL_RIGHT groups");
            }
            var model = new RailModel(imported, top);
            models.put(asset, model);
            return model;
        } catch (IOException | RuntimeException exception) {
            var failure = new IOException("IR rail model " + asset.entry() + ": " + exception.getMessage(), exception);
            failedModels.put(asset, failure);
            throw failure;
        }
    }

    private static String modelReference(JsonObject json) throws IOException {
        if (!json.has("models") || !json.get("models").isJsonObject()) {
            throw new IOException("models must be an object containing a single >0 OBJ reference");
        }
        var models = json.getAsJsonObject("models");
        if (models.size() != 1) throw new IOException("Only a single >0 IR rail model is supported");
        var entry = models.entrySet().iterator().next();
        String condition = entry.getKey();
        boolean supported = false;
        if (condition.startsWith(">")) {
            try { supported = Double.parseDouble(condition.substring(1)) == 0; }
            catch (NumberFormatException ignored) { /* Report the unsupported condition below. */ }
        }
        if (!supported) throw new IOException("Unsupported IR rail model condition: " + condition + "; expected >0");
        return string(entry.getValue(), "models." + condition).replace('\\', '/');
    }

    private static double positive(JsonObject json, String field, double defaultValue) throws IOException {
        if (!json.has(field)) return defaultValue;
        var value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException(field + " must be a finite positive number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number <= 0) throw new IOException(field + " must be a finite positive number");
        return number;
    }

    private static String string(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IOException(field + " must be a nonblank string");
        }
        return value.getAsString();
    }
}
