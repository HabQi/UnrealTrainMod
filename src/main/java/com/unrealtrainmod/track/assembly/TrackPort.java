/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import java.util.Objects;
import java.util.UUID;

/** The yaw points out of the assembly, toward its external track. */
public record TrackPort(UUID nodeId, double outwardYaw) {
    public TrackPort {
        Objects.requireNonNull(nodeId);
        if (!Double.isFinite(outwardYaw)) throw new IllegalArgumentException("Invalid track port yaw");
        outwardYaw = ((outwardYaw % 360) + 540) % 360 - 180;
    }
}
