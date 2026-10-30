/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.Objects;
import java.util.UUID;

/** A connection is established by this ID, never by spatial proximity. */
public record TrackNode(UUID id, TrackVector position, double gauge, TrackCurveParameters curve,
                        TrackVerticalCurveParameters verticalCurve) {
    public TrackNode(UUID id, TrackVector position, double gauge, TrackCurveParameters curve) {
        this(id, position, gauge, curve, null);
    }

    public TrackNode(UUID id, TrackVector position, double gauge) {
        this(id, position, gauge, null, null);
    }

    public TrackNode {
        Objects.requireNonNull(id);
        Objects.requireNonNull(position);
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z())) {
            throw new TrackGraphException(TrackGraphException.Reason.INVALID_POSITION);
        }
        if (!Double.isFinite(gauge) || gauge <= 0) {
            throw new TrackGraphException(TrackGraphException.Reason.INVALID_GAUGE);
        }
    }
}
