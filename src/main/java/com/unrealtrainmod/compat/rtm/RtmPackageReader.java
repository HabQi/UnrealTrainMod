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
    private static final String TRAIN_DIRECTORY = "mods/RTM/train/";
    private static final String RAIL_DIRECTORY = "mods/RTM/rail/";
    private static final Pattern TRAIN = Pattern.compile("^(?:[^/]+/)?mods/RTM/train/ModelTrain[^/]*\\.json$");
    private static final Pattern RAIL = Pattern.compile("^(?:[^/]+/)?mods/RTM/rail/ModelRail[^/]*\\.json$");
    private final RtmVehicleAdapter vehicles;
    private final RtmRailAdapter rails;

    public RtmPackageReader(Map<ResourceLocation, VehicleDefinition> vehicles,
                            Map<ResourceLocation, RailDefinition> rails, LoadReport report) {
        this.vehicles = new RtmVehicleAdapter(vehicles, report);
        this.rails = new RtmRailAdapter(rails, report);
    }

    public void read(PackageArchive archive) {
        for (String entry : archive.entries()) {
            if (TRAIN.matcher(entry).matches()) {
                vehicles.read(archive, entry, entry.substring(0, entry.indexOf(TRAIN_DIRECTORY)));
            }
        }
        for (String entry : archive.entries()) {
            if (RAIL.matcher(entry).matches()) {
                rails.read(archive, entry, entry.substring(0, entry.indexOf(RAIL_DIRECTORY)));
            }
        }
    }
}
