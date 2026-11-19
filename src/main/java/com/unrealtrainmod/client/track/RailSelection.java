/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.track.model.RailDefinition;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Package-rail metadata shared by the model selector. */
public record RailSelection(ResourceLocation id, RailDefinition rail) {
    public static List<RailSelection> all() {
        var result = new ArrayList<RailSelection>();
        for (var id : com.unrealtrainmod.track.model.NativeTurnoutCatalog.all()) result.add(new RailSelection(id, null));
        for (var rail : RailCatalog.all()) result.add(new RailSelection(rail.id(), rail));
        return List.copyOf(result);
    }

    public static RailSelection find(ResourceLocation id) {
        if (com.unrealtrainmod.track.model.NativeTurnoutCatalog.contains(id)) return new RailSelection(id, null);
        var rail = RailCatalog.find(id);
        return rail == null ? null : new RailSelection(id, rail);
    }

    public Component name() { return rail == null ? Component.translatable(com.unrealtrainmod.track.model.NativeTurnoutCatalog.nameKey(id)) : Component.literal(rail.railName()); }
    public String group() { return rail == null ? "unrealtrainmod:builtin" : rail.config().archive().toString(); }
    public Component groupName() { return rail == null ? Component.translatable("gui.unrealtrainmod.rail_models.builtin") : Component.literal(rail.packName()); }
    public String detail() { return rail == null ? Component.translatable(com.unrealtrainmod.track.model.NativeTurnoutCatalog.DOUBLE_SLIP_R11.equals(id)
            ? "gui.unrealtrainmod.rail_models.native_double_slip_scope" : com.unrealtrainmod.track.model.NativeTurnoutCatalog.SCISSORS_R11.equals(id)
            ? "gui.unrealtrainmod.rail_models.native_scissors_scope" : com.unrealtrainmod.track.model.NativeTurnoutCatalog.DIAMOND_R11.equals(id)
            ? "gui.unrealtrainmod.rail_models.native_diamond_scope" : "gui.unrealtrainmod.rail_models.native_scope").getString()
            : rail.tags() + " · " + id + (rail.source().format() == RailDefinition.Format.MQO
            ? " · " + Component.translatable("gui.unrealtrainmod.rail_models.rtm_scope").getString() : ""); }
}
