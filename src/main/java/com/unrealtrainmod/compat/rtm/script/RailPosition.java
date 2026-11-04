/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/** Virtual legacy tile anchor: direction zero uses the block center, independently of native node placement. */
public final class RailPosition {
    public static final double[][] REVISION = {{0, 0}};
    private RailPosition() {}
}
