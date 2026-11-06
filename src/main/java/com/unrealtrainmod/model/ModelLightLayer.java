/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

/** RTM texture layers; head and tail are independent switches on the owning vehicle. */
public enum ModelLightLayer {
    BASE, OFF, HEAD, TAIL;

    public boolean enabled(int lights) {
        return switch (this) {
            case BASE -> true;
            case OFF -> lights == 0;
            case HEAD -> (lights & 1) != 0;
            case TAIL -> (lights & 2) != 0;
        };
    }

    public boolean emissive() { return this == HEAD || this == TAIL; }
}
