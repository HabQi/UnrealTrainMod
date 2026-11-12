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
        for (var rail : RailCatalog.all()) result.add(new RailSelection(rail.id(), rail));
        return List.copyOf(result);
    }

    public static RailSelection find(ResourceLocation id) {
        var rail = RailCatalog.find(id);
        return rail == null ? null : new RailSelection(id, rail);
    }

    public Component name() { return Component.literal(rail.railName()); }
    public String group() { return rail.config().archive().toString(); }
    public Component groupName() { return Component.literal(rail.packName()); }
    public String detail() { return rail.tags() + " · " + id + (rail.source().format() == RailDefinition.Format.MQO
            ? " · " + Component.translatable("gui.unrealtrainmod.rail_models.rtm_scope").getString() : ""); }
}
