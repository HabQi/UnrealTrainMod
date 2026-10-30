/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

public enum TurnoutDirection {
    LEFT(-1), RIGHT(1);

    private final int side;

    TurnoutDirection(int side) { this.side = side; }
    public int side() { return side; }
}
