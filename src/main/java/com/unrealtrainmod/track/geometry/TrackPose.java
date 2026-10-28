/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

/**
 * Position in blocks and an orthonormal frame. Lateral is UP cross tangent;
 * normal is tangent cross lateral. Curvature is signed horizontal curvature
 * toward lateral, in inverse blocks. Gauge is in blocks.
 */
public record TrackPose(TrackVector position, TrackVector tangent, TrackVector lateral,
                        TrackVector normal, double curvature, double gauge) {
    TrackPose reversed() {
        return new TrackPose(position, tangent.negated(), lateral.negated(), normal, -curvature, gauge);
    }
}
