/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

/** The five independent construction tools; only implemented types create drafts. */
public enum TrackConstructionType {
    SIMPLE_TURNOUT(3), DIAMOND_CROSSING(4), SINGLE_CROSSOVER(4), SCISSORS_CROSSOVER(0), DOUBLE_SLIP(0);

    private final int pointCount;

    TrackConstructionType(int pointCount) { this.pointCount = pointCount; }
    public int pointCount() { return pointCount; }
    public boolean implemented() { return pointCount != 0; }
}
