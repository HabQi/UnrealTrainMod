/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;

/** Startup catalog shared by both logical sides; contains no loading or rendering logic. */
public final class RailCatalog {
    private static List<RailDefinition> models = List.of();
    private static Map<ResourceLocation, RailDefinition> byId = Map.of();

    private RailCatalog() {}

    public static void register(List<RailDefinition> definitions) {
        var snapshot = List.copyOf(definitions);
        var index = snapshot.stream().collect(Collectors.toUnmodifiableMap(RailDefinition::id, model -> model));
        models = snapshot;
        byId = index;
    }

    public static List<RailDefinition> all() { return models; }
    public static RailDefinition find(ResourceLocation id) { return id == null ? null : byId.get(id); }
}
