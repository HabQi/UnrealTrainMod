/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.model;

import com.unrealtrainmod.track.construction.TrackConstructionType;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Built-in selection metadata exists on both logical sides, independently of package archives. */
public final class NativeTurnoutCatalog {
    public static final ResourceLocation R11 = ResourceLocation.fromNamespaceAndPath("unrealtrainmod", "native_turnout/r11");
    public static final ResourceLocation DIAMOND_R11 = ResourceLocation.fromNamespaceAndPath("unrealtrainmod", "native_diamond/r11");
    public static final ResourceLocation SCISSORS_R11 = ResourceLocation.fromNamespaceAndPath("unrealtrainmod", "native_scissors/r11");
    private NativeTurnoutCatalog() {}
    public static boolean contains(ResourceLocation id) { return R11.equals(id) || DIAMOND_R11.equals(id) || SCISSORS_R11.equals(id); }
    public static boolean contains(String id) { return R11.toString().equals(id) || isDiamond(id) || SCISSORS_R11.toString().equals(id); }
    public static boolean isDiamond(String id) { return DIAMOND_R11.toString().equals(id); }
    public static List<ResourceLocation> all() { return List.of(R11, DIAMOND_R11, SCISSORS_R11); }
    public static String nameKey(ResourceLocation id) {
        return "gui.unrealtrainmod.rail_models."+(SCISSORS_R11.equals(id) ? "native_scissors" : DIAMOND_R11.equals(id) ? "native_diamond" : "native_turnout");
    }
    public static boolean supports(ResourceLocation id, TrackConstructionType kind) {
        return R11.equals(id) && kind == TrackConstructionType.SIMPLE_TURNOUT
                || DIAMOND_R11.equals(id) && kind == TrackConstructionType.DIAMOND_CROSSING
                || SCISSORS_R11.equals(id) && kind == TrackConstructionType.SCISSORS_CROSSOVER;
    }

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
