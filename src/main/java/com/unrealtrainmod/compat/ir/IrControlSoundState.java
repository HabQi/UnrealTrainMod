/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

/** Runtime-only edge counters preserve a click whose press and release share one tracking update. */
public record IrControlSoundState(boolean pressed, long presses, long releases, long moves) {
    public static final IrControlSoundState IDLE = new IrControlSoundState(false, 0, 0, 0);
}
