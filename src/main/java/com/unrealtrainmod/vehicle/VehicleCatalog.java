/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;

/** Startup catalog shared by both logical sides; contains no loading or rendering logic. */
public final class VehicleCatalog {
    private static List<VehicleDefinition> models = List.of();
    private static Map<ResourceLocation, VehicleDefinition> byId = Map.of();

    private VehicleCatalog() {}

    public static void register(List<VehicleDefinition> definitions) {
        var snapshot = List.copyOf(definitions);
        var index = snapshot.stream().collect(Collectors.toUnmodifiableMap(VehicleDefinition::id, model -> model));
        models = snapshot;
        byId = index;
    }

    public static List<VehicleDefinition> all() { return models; }
    public static VehicleDefinition find(ResourceLocation id) { return id == null ? null : byId.get(id); }
}
