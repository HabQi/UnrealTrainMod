/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.Objects;
import java.util.UUID;

/** A selected position. A non-null node ID explicitly reuses that node; its position is a snapshot. */
public record TrackPlacementEndpoint(UUID nodeId, TrackVector position) {
    public TrackPlacementEndpoint {
        Objects.requireNonNull(position);
    }
}
