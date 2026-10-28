/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

/** Emitted only after a live graph mutation has committed. */
public record TrackSegmentChange(Type type, TrackSegment segment) {
    public enum Type { ADDED, REMOVED }
}
