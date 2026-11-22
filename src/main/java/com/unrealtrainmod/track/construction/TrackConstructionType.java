/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** Structure topology selected by a track entry. */
public enum TrackConstructionType implements StringRepresentable {
    SIMPLE_TURNOUT(3), DIAMOND_CROSSING(4), SINGLE_CROSSOVER(4), SCISSORS_CROSSOVER(4), DOUBLE_SLIP(4);

    public static final Codec<TrackConstructionType> CODEC = StringRepresentable.fromEnum(TrackConstructionType::values);
    private final int pointCount;

    TrackConstructionType(int pointCount) { this.pointCount = pointCount; }
    public int pointCount() { return pointCount; }
    @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
}
