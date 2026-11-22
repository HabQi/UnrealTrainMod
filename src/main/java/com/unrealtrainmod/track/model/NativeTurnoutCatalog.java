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
    public static TrackConstructionType constructionType(ResourceLocation id) {
        if (R11.equals(id)) return TrackConstructionType.SIMPLE_TURNOUT;
        if (DIAMOND_R11.equals(id)) return TrackConstructionType.DIAMOND_CROSSING;
        if (SINGLE_CROSSOVER_R11.equals(id)) return TrackConstructionType.SINGLE_CROSSOVER;
        if (SCISSORS_R11.equals(id)) return TrackConstructionType.SCISSORS_CROSSOVER;
        if (DOUBLE_SLIP_R11.equals(id)) return TrackConstructionType.DOUBLE_SLIP;
        return null;
    }
}
