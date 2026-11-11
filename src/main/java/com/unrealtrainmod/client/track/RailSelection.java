/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.track.model.RailDefinition;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Selector metadata without inventing package assets for built-in styles. */
public record RailSelection(ResourceLocation id, RailDefinition rail) {
    public static List<RailSelection> all() {
        var result = new ArrayList<RailSelection>();
        result.add(new RailSelection(NativeTurnoutCatalog.DEFAULT, null));
        for (var rail : RailCatalog.all()) result.add(new RailSelection(rail.id(), rail));
        return List.copyOf(result);
    }

    public static RailSelection find(ResourceLocation id) {
        if (NativeTurnoutCatalog.contains(id)) return new RailSelection(id, null);
        var rail = RailCatalog.find(id);
        return rail == null ? null : new RailSelection(id, rail);
    }

    public Component name() { return rail == null ? Component.translatable("gui.unrealtrainmod.native_turnout.default") : Component.literal(rail.railName()); }
    public String group() { return rail == null ? Component.translatable("gui.unrealtrainmod.native_turnout.group").getString() : rail.config().archive().toString(); }
    public Component groupName() { return rail == null ? Component.translatable("gui.unrealtrainmod.native_turnout.group") : Component.literal(rail.packName()); }
    public String detail() { return rail == null ? Component.translatable("gui.unrealtrainmod.native_turnout.description").getString() : rail.tags() + " · " + id + (rail.source().format() == RailDefinition.Format.MQO
            ? " · " + Component.translatable("gui.unrealtrainmod.native_turnout.rtm_scope").getString() : ""); }
}
