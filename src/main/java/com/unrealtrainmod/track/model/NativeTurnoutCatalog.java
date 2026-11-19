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
    public static final ResourceLocation SINGLE_CROSSOVER_R11 = ResourceLocation.fromNamespaceAndPath("unrealtrainmod", "native_single_crossover/r11");
    public static final ResourceLocation DOUBLE_SLIP_R11 = ResourceLocation.fromNamespaceAndPath("unrealtrainmod", "native_double_slip/r11");
    private static final List<ResourceLocation> MODELS = List.of(R11, DIAMOND_R11, SCISSORS_R11, SINGLE_CROSSOVER_R11, DOUBLE_SLIP_R11);
    private NativeTurnoutCatalog() {}
    public static boolean contains(ResourceLocation id) { return id != null && MODELS.contains(id); }
    public static boolean contains(String id) { return R11.toString().equals(id) || isDiamond(id) || isCrossover(id) || isDoubleSlip(id); }
    public static boolean isCrossover(String id) { return SCISSORS_R11.toString().equals(id) || SINGLE_CROSSOVER_R11.toString().equals(id); }
    public static boolean isDiamond(String id) { return DIAMOND_R11.toString().equals(id); }
    public static boolean isDoubleSlip(String id) { return DOUBLE_SLIP_R11.toString().equals(id); }
    public static List<ResourceLocation> all() { return MODELS; }
    public static String nameKey(ResourceLocation id) {
        return "gui.unrealtrainmod.rail_models."+(DOUBLE_SLIP_R11.equals(id) ? "native_double_slip"
                : SINGLE_CROSSOVER_R11.equals(id) ? "native_single_crossover" : SCISSORS_R11.equals(id) ? "native_scissors"
                : DIAMOND_R11.equals(id) ? "native_diamond" : "native_turnout");
    }
    public static boolean supports(ResourceLocation id, TrackConstructionType kind) {
        return R11.equals(id) && kind == TrackConstructionType.SIMPLE_TURNOUT
                || DIAMOND_R11.equals(id) && kind == TrackConstructionType.DIAMOND_CROSSING
                || SCISSORS_R11.equals(id) && kind == TrackConstructionType.SCISSORS_CROSSOVER
                || SINGLE_CROSSOVER_R11.equals(id) && kind == TrackConstructionType.SINGLE_CROSSOVER
                || DOUBLE_SLIP_R11.equals(id) && kind == TrackConstructionType.DOUBLE_SLIP;
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
