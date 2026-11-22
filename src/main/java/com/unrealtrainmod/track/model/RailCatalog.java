/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.model;

import com.unrealtrainmod.track.construction.TrackConstructionType;
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

    public record Selection(ResourceLocation modelId, TrackConstructionType variant) {
        public TrackConstructionType constructionType() {
            if (variant != null) {
                var rail = find(modelId);
                return rail != null && rail.source().format() == RailDefinition.Format.IR_OBJ
                        && (variant == TrackConstructionType.DIAMOND_CROSSING || variant == TrackConstructionType.SINGLE_CROSSOVER)
                        ? variant : null;
            }
            var nativeType = NativeTurnoutCatalog.constructionType(modelId);
            return nativeType != null ? nativeType : find(modelId) != null ? TrackConstructionType.SIMPLE_TURNOUT : null;
        }

        public boolean forOrdinaryTrack() {
            return variant == null && !NativeTurnoutCatalog.contains(modelId);
        }
    }

    public static List<RailDefinition> all() { return models; }
    public static boolean contains(ResourceLocation id) { return NativeTurnoutCatalog.contains(id) || find(id) != null; }
    public static RailDefinition find(ResourceLocation id) { return id == null ? null : byId.get(id); }
}
