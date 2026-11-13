/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.model;

import net.minecraft.resources.ResourceLocation;

/** Built-in selection metadata exists on both logical sides, independently of package archives. */
public final class NativeTurnoutCatalog {
    public static final ResourceLocation R11 = ResourceLocation.fromNamespaceAndPath("unrealtrainmod", "native_turnout/r11");
    private NativeTurnoutCatalog() {}
    public static boolean contains(ResourceLocation id) { return R11.equals(id); }
    public static boolean contains(String id) { return R11.toString().equals(id); }

    /** The held placer wins; construction tools use the first placer in hotbar order. */
    public static ResourceLocation constructionModel(net.minecraft.world.entity.player.Player player) {
        var held = player.getMainHandItem();
        var placer = com.unrealtrainmod.UnrealTrainMod.RAIL_PLACER.get();
        var component = com.unrealtrainmod.registry.ModDataComponents.SELECTED_RAIL_MODEL.get();
        if (held.is(placer)) return held.get(component);
        for (int slot = 0; slot < 9; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(placer)) return stack.get(component);
        }
        return null;
    }
}
