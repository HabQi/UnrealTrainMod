/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.loading;

import com.unrealtrainmod.compat.ir.IrPackageReader;
import com.unrealtrainmod.compat.rtm.RtmPackageReader;
import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.track.model.RailDefinition;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.vehicle.VehicleMotionOverrides;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;

/** Explicit startup orchestration; only this entry point publishes resource catalogs. */
public final class ResourceLoader {
    private ResourceLoader() {}

    public static void load(Path modsDirectory, Path configDirectory) {
        var report = new LoadReport();
        var vehicles = new LinkedHashMap<ResourceLocation, VehicleDefinition>();
        var rails = new LinkedHashMap<ResourceLocation, RailDefinition>();
        Path rtmDirectory = modsDirectory.resolve("Packages_RTM");
        Path irDirectory = modsDirectory.resolve("Packages_IR");
        var rtm = new RtmPackageReader(vehicles, rails, report);
        var ir = new IrPackageReader(vehicles, rails, report);
        for (var path : archives(rtmDirectory, report)) {
            try (var archive = new PackageArchive(path)) {
                rtm.read(archive);
            } catch (IOException | RuntimeException exception) {
                report.archiveFailed(true, path, exception);
            }
        }
        for (var path : archives(irDirectory, report)) {
            try (var archive = new PackageArchive(path)) {
                ir.read(archive);
            } catch (IOException | RuntimeException exception) {
                report.archiveFailed(false, path, exception);
            }
        }
        VehicleMotionOverrides.apply(configDirectory.resolve("unrealtrainmod/vehicle-motion.json"), vehicles);
        VehicleCatalog.register(List.copyOf(vehicles.values()));
        var railDefinitions = List.copyOf(rails.values());
        RailCatalog.register(railDefinitions);
        report.logSummary(LoadReport.Kind.RTM_VEHICLE, rtmDirectory, "");
        report.logSummary(LoadReport.Kind.IR_VEHICLE, irDirectory, "");
        var rtmRails = railDefinitions.stream().filter(definition -> definition.source().format() == RailDefinition.Format.MQO).toList();
        var usage = new HashMap<PackageAsset, Integer>();
        for (var definition : rtmRails) usage.merge(definition.source().model(), 1, Integer::sum);
        long shared = usage.values().stream().filter(count -> count > 1).count();
        long scripts = rtmRails.stream().filter(definition -> definition.source().rendererPath() != null).count();
        report.logSummary(LoadReport.Kind.RTM_RAIL, rtmDirectory, usage.size() + " unique MQO models (" + shared
                + " shared, " + (rtmRails.size() - usage.size()) + " reused references), "
                + scripts + " declared scripts (not executed)");
        long irModels = railDefinitions.stream().filter(definition -> definition.source().format() == RailDefinition.Format.IR_OBJ)
                .map(definition -> definition.source().model()).distinct().count();
        report.logSummary(LoadReport.Kind.IR_RAIL, irDirectory, irModels + " unique OBJ models; name must contain rail or Rail");
    }

    private static List<Path> archives(Path directory, LoadReport report) {
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                return files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            }
        } catch (IOException | RuntimeException exception) {
            report.directoryFailed(directory, exception);
            return List.of();
        }
    }
}
