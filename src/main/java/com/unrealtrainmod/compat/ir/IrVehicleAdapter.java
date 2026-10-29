/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.google.gson.JsonObject;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.MaterialRenderMode;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.model.ObjParser;
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
import java.util.HashMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/** Converts IR stock configurations while preserving lightweight startup bounds scanning. */
final class IrVehicleAdapter {
    private static final float STANDARD_GAUGE = 1.435f;
    private final Map<ResourceLocation, VehicleDefinition> definitions;
    private final Map<PackageAsset, Map<String, ModelBounds>> boundsCache = new HashMap<>();
    private final LoadReport report;

    IrVehicleAdapter(Map<ResourceLocation, VehicleDefinition> definitions, LoadReport report) {
        this.definitions = definitions;
        this.report = report;
    }

    void read(PackageArchive archive, String prefix, String type, String reference) {
        var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "ir/" + ResourceIds.hash(reference));
        var config = new PackageAsset(archive.path(), prefix + "assets/" + reference.replace(':', '/'));
        var retained = definitions.get(id);
        if (retained != null) {
            report.duplicate(LoadReport.Kind.IR_VEHICLE, id, config, retained.config());
            return;
        }
        try {
            config = archive.resolve(prefix, reference);
            var definition = readDefinition(id, type, config, prefix, archive);
            definitions.put(id, definition);
            report.registered(LoadReport.Kind.IR_VEHICLE);
        } catch (IOException | RuntimeException exception) {
            report.failed(LoadReport.Kind.IR_VEHICLE, config, exception);
        }
    }

    private VehicleDefinition readDefinition(ResourceLocation id, String type, PackageAsset config,
                                             String prefix, PackageArchive archive) throws IOException {
        var json = readJson(archive, config.entry());
        String name = GsonHelper.getAsString(json, "name");
        if (name.isBlank()) throw new IOException("name must not be blank");
        String reference = GsonHelper.getAsString(json, "model");
        if (!reference.toLowerCase(Locale.ROOT).endsWith(".obj")) throw new IOException("IR model must be OBJ: " + reference);
        if (reference.indexOf(':') < 0) reference = "immersiverailroading:" + reference;
        var model = archive.resolve(prefix, reference);
        float modelGauge = GsonHelper.getAsFloat(json, "model_gauge_m", STANDARD_GAUGE);
        float gauge = GsonHelper.getAsFloat(json, "recommended_gauge_m", modelGauge);
        if (!Float.isFinite(modelGauge) || modelGauge <= 0 || !Float.isFinite(gauge) || gauge <= 0) {
            throw new IOException("model_gauge_m and recommended_gauge_m must be finite and positive");
        }
        float scale = gauge / modelGauge;
        if (!Float.isFinite(scale) || scale <= 0) throw new IOException("Invalid IR model gauge scale");
        var rawParts = boundsCache.get(model);
        if (rawParts == null) {
            try (var reader = new InputStreamReader(archive.open(model.entry()), StandardCharsets.UTF_8.newDecoder())) {
                rawParts = ObjParser.scanPartBounds(reader, model.entry(), IrModelLoader::isVisible);
            }
            boundsCache.put(model, rawParts);
        }
        var groups = new EnumMap<VehiclePartRole, ModelBounds.Builder>(VehiclePartRole.class);
        var combined = new ModelBounds.Builder();
        rawParts.forEach((part, raw) -> {
            // Match the importer's proper Y rotation and scale, retaining the authored origin.
            var rotated = new ModelBounds(raw.minZ() * scale, raw.minY() * scale, -raw.maxX() * scale,
                    raw.maxZ() * scale, raw.maxY() * scale, -raw.minX() * scale);
            combined.include(rotated);
            groups.computeIfAbsent(IrBogieGroups.role(part), ignored -> new ModelBounds.Builder()).include(rotated);
        });
        var bounds = combined.build();
        var spec = readMotionSpec(json, bounds, scale);
        var source = new ModelSource(model, Map.of(), Map.of("default", MaterialRenderMode.AUTO),
                false, false, ModelSource.Format.IR_OBJ, scale);
        var parts = new ArrayList<VehicleDefinition.Instance>();
        groups.forEach((role, builder) -> {
            double z = switch (role) {
                case BODY -> 0;
                case FRONT_BOGIE -> spec.frontBogieOffset();
                case REAR_BOGIE -> spec.rearBogieOffset();
            };
            parts.add(new VehicleDefinition.Instance(source, new ModelData.Position(0, 0, 0), role,
                    new TrackVector(0, 0, z), builder.build()));
        });
        return new VehicleDefinition(id, name, config, null, type, parts, bounds, spec);
    }

    private static VehicleMotionSpec readMotionSpec(JsonObject json, ModelBounds bounds, double scale) {
        var base = VehicleMotionSpec.fromBounds(bounds, 0);
        var pivot = json.has("trucks") ? GsonHelper.getAsJsonObject(json, "trucks")
                : GsonHelper.getAsJsonObject(json, "pivot", new JsonObject());
        // IR's signed pivot distances already use front-positive, matching imported +Z.
        double frontBogie = pivot.has("front") ? GsonHelper.getAsDouble(pivot, "front") * scale : base.frontBogieOffset();
        double rearBogie = pivot.has("rear") ? GsonHelper.getAsDouble(pivot, "rear") * scale : base.rearBogieOffset();
        var couplers = GsonHelper.getAsJsonObject(json, "couplers", new JsonObject());
        double frontCoupler = bounds.maxZ() + GsonHelper.getAsDouble(couplers, "front_offset", 0) * scale;
        double rearCoupler = bounds.minZ() - GsonHelper.getAsDouble(couplers, "rear_offset", 0) * scale;
        return new VehicleMotionSpec(base.length(), base.width(), base.height(), base.trackDatum(),
                frontCoupler, rearCoupler, frontBogie, rearBogie, 0);
    }

    static JsonObject readJson(PackageArchive archive, String entry) throws IOException {
        try (var reader = new InputStreamReader(archive.open(entry), StandardCharsets.UTF_8.newDecoder())) {
            // Keep IR trailing-comma compatibility, including animation arrays not yet interpreted.
            return GsonHelper.parse(reader, true);
        }
    }
}
