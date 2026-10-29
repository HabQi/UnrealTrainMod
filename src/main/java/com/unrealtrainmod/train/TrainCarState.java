/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Persistent member identity, fixed route offset and last committed anchor; no live entity reference. */
public record TrainCarState(UUID entityId, ResourceLocation modelId, double offset, TrainAnchor anchor) {
    TrainCarState withAnchor(TrainAnchor value) { return new TrainCarState(entityId, modelId, offset, value); }
    TrainCarState asSingle() { return new TrainCarState(entityId, modelId, 0, anchor); }
}
