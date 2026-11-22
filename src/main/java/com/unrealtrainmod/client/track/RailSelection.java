/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.construction.TrackConstructionType;
import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.track.model.RailDefinition;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Package-rail metadata shared by the model selector. */
public record RailSelection(ResourceLocation id, RailDefinition rail, TrackConstructionType variant) {
    public static List<RailSelection> all() {
        var result = new ArrayList<RailSelection>();
        for (var id : NativeTurnoutCatalog.all()) result.add(new RailSelection(id, null, null));
        for (var rail : RailCatalog.all()) {
            result.add(new RailSelection(rail.id(), rail, null));
            if (rail.source().format() == RailDefinition.Format.IR_OBJ) {
                result.add(new RailSelection(rail.id(), rail, TrackConstructionType.DIAMOND_CROSSING));
                result.add(new RailSelection(rail.id(), rail, TrackConstructionType.SINGLE_CROSSOVER));
            }
        }
        return List.copyOf(result);
    }

    public boolean matches(RailCatalog.Selection selection) {
        return id.equals(selection.modelId()) && variant == selection.variant();
    }

    public Component name() {
        if (rail == null) return Component.translatable(NativeTurnoutCatalog.nameKey(id));
        return variant == null ? Component.literal(rail.railName())
                : Component.translatable("gui.unrealtrainmod.rail_models.variant." + variant.getSerializedName(), rail.railName());
    }
    public String group() { return rail == null ? "unrealtrainmod:builtin" : rail.config().archive().toString(); }
    public Component groupName() { return rail == null ? Component.translatable("gui.unrealtrainmod.rail_models.builtin") : Component.literal(rail.packName()); }
    public String detail() {
        String prefix = "gui.unrealtrainmod.rail_models.";
        if (rail == null) {
            String scope = switch (NativeTurnoutCatalog.constructionType(id)) {
                case SIMPLE_TURNOUT -> "native_scope";
                case DIAMOND_CROSSING -> "native_diamond_scope";
                case SINGLE_CROSSOVER -> "native_single_crossover_scope";
                case SCISSORS_CROSSOVER -> "native_scissors_scope";
                case DOUBLE_SLIP -> "native_double_slip_scope";
            };
            return Component.translatable(prefix + scope).getString();
        }
        String scope = variant == null ? "generic_scope" : "four_points_scope";
        return rail.tags() + " · " + id + " · " + Component.translatable(prefix + scope).getString();
    }
}
