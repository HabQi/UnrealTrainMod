/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

/** An immutable double-precision world position or direction, independent of rendering. */
public record TrackVector(double x, double y, double z) {
    TrackVector negated() {
        return new TrackVector(-x, -y, -z);
    }
}
