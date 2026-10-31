/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm;

import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.track.model.RailDefinition;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;

/** Discovers supported RTM configuration entries inside one shared ZIP session. */
public final class RtmPackageReader {
    private static final Pattern TRAIN = Pattern.compile(
            "^([^/]+/)?(?:mods/RTM/train/|assets/minecraft/models/json/)ModelTrain[^/]*\\.json$");
    private static final Pattern RAIL = Pattern.compile(
            "^([^/]+/)?(?:mods/RTM/rail/|assets/minecraft/models/json/)ModelRail[^/]*\\.json$");
    private final RtmVehicleAdapter vehicles;
    private final RtmRailAdapter rails;

    public RtmPackageReader(Map<ResourceLocation, VehicleDefinition> vehicles,
                            Map<ResourceLocation, RailDefinition> rails, LoadReport report) {
        this.vehicles = new RtmVehicleAdapter(vehicles, report);
        this.rails = new RtmRailAdapter(rails, report);
    }

    public void read(PackageArchive archive) {
        for (String entry : archive.entries()) {
            var matcher = TRAIN.matcher(entry);
            if (matcher.matches()) {
                vehicles.read(archive, entry, matcher.group(1) == null ? "" : matcher.group(1));
            }
        }
        for (String entry : archive.entries()) {
            var matcher = RAIL.matcher(entry);
            if (matcher.matches()) {
                rails.read(archive, entry, matcher.group(1) == null ? "" : matcher.group(1));
            }
        }
    }
}
