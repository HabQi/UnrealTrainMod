/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Persistent member identity, fixed route offset and last committed anchor; no live entity reference. */
public record TrainCarState(UUID entityId, ResourceLocation modelId, double offset, boolean reversed, TrainAnchor anchor) {
    public int direction() { return reversed ? -1 : 1; }
    TrainCarState withAnchor(TrainAnchor value) { return new TrainCarState(entityId, modelId, offset, reversed, value); }
    TrainCarState asSingle() { return new TrainCarState(entityId, modelId, 0, false, anchor); }
}
