/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Persistent member identity, fixed route offset and last committed anchor; no live entity reference. */
public record TrainCarState(UUID entityId, ResourceLocation modelId, double offset, boolean reversed, TrainAnchor anchor,
                            UUID previousTrainId) {
    public TrainCarState(UUID entityId, ResourceLocation modelId, double offset, boolean reversed, TrainAnchor anchor) {
        this(entityId, modelId, offset, reversed, anchor, null);
    }
    public int direction() { return reversed ? -1 : 1; }
    TrainCarState withAnchor(TrainAnchor value) { return new TrainCarState(entityId, modelId, offset, reversed, value, previousTrainId); }
}
