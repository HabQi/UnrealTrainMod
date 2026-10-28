/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;

/** Reads IR catalogs in entry order and preserves their authored stock-list order. */
public final class IrPackageReader {
    private static final Pattern CATALOG = Pattern.compile("^([^/]+/)?assets/immersiverailroading/rolling_stock/stock\\.json$");
    private static final List<String> TYPES = List.of("locomotives", "passenger", "freight", "tank", "tender", "hand_car");
    private final IrVehicleAdapter vehicles;
    private final LoadReport report;

    public IrPackageReader(Map<ResourceLocation, VehicleDefinition> vehicles, LoadReport report) {
        this.vehicles = new IrVehicleAdapter(vehicles, report);
        this.report = report;
    }

    public void read(PackageArchive archive) {
        boolean found = false;
        for (String entry : archive.entries()) {
            var matcher = CATALOG.matcher(entry);
            if (!matcher.matches()) continue;
            found = true;
            String prefix = matcher.group(1) == null ? "" : matcher.group(1);
            var catalog = new PackageAsset(archive.path(), entry);
            try {
                var json = IrVehicleAdapter.readJson(archive, entry);
                for (String type : TYPES) {
                    if (!json.has(type)) continue;
                    if (!json.get(type).isJsonArray()) {
                        report.catalogFailed(catalog, new IOException("'" + type + "' must be a list of stock names"));
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
            } catch (IOException | RuntimeException exception) {
                report.catalogFailed(catalog, exception);
            }
        }
        if (!found) report.missingCatalog(archive.path());
    }
}
