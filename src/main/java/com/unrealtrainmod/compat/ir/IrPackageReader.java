/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.google.gson.JsonObject;
import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.track.model.RailDefinition;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;

/** Reads independent IR stock and track catalogs, preserving their authored list order. */
public final class IrPackageReader {
    private static final Pattern CATALOG = Pattern.compile(
            "^([^/]+/)?assets/immersiverailroading/(rolling_stock/stock|track/track)\\.json$");
    private static final List<String> TYPES = List.of("locomotives", "passenger", "freight", "tank", "tender", "hand_car");
    private final IrVehicleAdapter vehicles;
    private final IrRailAdapter rails;
    private final LoadReport report;

    public IrPackageReader(Map<ResourceLocation, VehicleDefinition> vehicles,
                           Map<ResourceLocation, RailDefinition> rails, LoadReport report) {
        this.vehicles = new IrVehicleAdapter(vehicles, report);
        this.rails = new IrRailAdapter(rails, report);
        this.report = report;
    }

    public void read(PackageArchive archive) {
        boolean found = false;
        for (String entry : archive.entries()) {
            var matcher = CATALOG.matcher(entry);
            if (!matcher.matches()) continue;
            found = true;
            String prefix = matcher.group(1) == null ? "" : matcher.group(1);
            var kind = matcher.group(2).equals("track/track") ? LoadReport.Kind.IR_RAIL : LoadReport.Kind.IR_VEHICLE;
            var catalog = new PackageAsset(archive.path(), entry);
            try {
                var json = IrVehicleAdapter.readJson(archive, entry);
                if (kind == LoadReport.Kind.IR_RAIL) readRails(archive, prefix, catalog, json);
                else readVehicles(archive, prefix, catalog, json);
            } catch (IOException | RuntimeException exception) {
                report.catalogFailed(kind, catalog, exception);
            }
        }
        if (!found) report.missingCatalog(archive.path());
    }

    private void readVehicles(PackageArchive archive, String prefix, PackageAsset catalog, JsonObject json) {
        for (String type : TYPES) {
            if (!json.has(type)) continue;
            if (!json.get(type).isJsonArray()) {
                report.catalogFailed(LoadReport.Kind.IR_VEHICLE, catalog,
                        new IOException("'" + type + "' must be a list of stock names"));
                continue;
            }
            for (var stock : json.getAsJsonArray(type)) {
                try {
                    if (!stock.isJsonPrimitive() || !stock.getAsJsonPrimitive().isString()
                            || stock.getAsString().isBlank()) {
                        throw new IOException(type + " stock name must be a nonblank string: " + stock);
                    }
                    String reference = ("immersiverailroading:rolling_stock/" + type + "/" + stock.getAsString() + ".json")
                            .replace('\\', '/');
                    vehicles.read(archive, prefix, type, reference);
                } catch (IOException | RuntimeException exception) {
                    report.failed(LoadReport.Kind.IR_VEHICLE, catalog, exception);
                }
            }
        }
    }

    private void readRails(PackageArchive archive, String prefix, PackageAsset catalog, JsonObject json) throws IOException {
        if (!json.has("types") || !json.get("types").isJsonArray()) {
            throw new IOException("'types' must be a list of track names");
        }
        for (var type : json.getAsJsonArray("types")) {
            try {
                if (!type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString() || type.getAsString().isBlank()) {
                    throw new IOException("Track name must be a nonblank string: " + type);
                }
                String reference = ("immersiverailroading:track/" + type.getAsString() + ".json").replace('\\', '/');
                rails.read(archive, prefix, reference);
            } catch (IOException | RuntimeException exception) {
                report.failed(LoadReport.Kind.IR_RAIL, catalog, exception);
            }
        }
    }
}
