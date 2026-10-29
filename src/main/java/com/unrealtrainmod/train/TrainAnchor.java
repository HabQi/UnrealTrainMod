/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Saved segment coordinates, independent of a temporary route's origin and segment indices. */
public record TrainAnchor(UUID segmentId, double distance, boolean fromStart) {
    public TrainAnchor {
        Objects.requireNonNull(segmentId);
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("Anchor distance must be finite and nonnegative");
        }
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("SegmentId", segmentId);
        tag.putDouble("Distance", distance);
        tag.putBoolean("FromStart", fromStart);
        return tag;
    }

    public static TrainAnchor load(CompoundTag tag) {
        if (!tag.hasUUID("SegmentId") || !tag.contains("Distance", Tag.TAG_DOUBLE)
                || !tag.contains("FromStart", Tag.TAG_BYTE)) {
            throw new IllegalArgumentException("RailAnchor requires SegmentId, Distance and FromStart");
        }
        return new TrainAnchor(tag.getUUID("SegmentId"), tag.getDouble("Distance"), tag.getBoolean("FromStart"));
    }
}
