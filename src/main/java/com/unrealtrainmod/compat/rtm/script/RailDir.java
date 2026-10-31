/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/** Legacy rail direction enum used by dynamic switch scripts. */
public enum RailDir {
    RIGHT(-1), LEFT(1), NONE(0);

    public final byte id;

    RailDir(int id) { this.id = (byte)id; }

    public RailDir invert() {
        return this == RIGHT ? LEFT : this == LEFT ? RIGHT : NONE;
    }
}
