/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public final class WiringCatalog {
    private static Map<ResourceLocation, WiringDefinition> definitions = Map.of();
    private WiringCatalog() {}
    public static void register(Map<ResourceLocation, WiringDefinition> values) { definitions = Map.copyOf(values); }
    public static WiringDefinition find(ResourceLocation id) { return id == null ? null : definitions.get(id); }
    public static List<WiringDefinition> all(boolean wire) {
        return definitions.values().stream().filter(d -> d.wire() == wire)
                .sorted(java.util.Comparator.comparing(d -> d.config().toString())).toList();
    }
}
