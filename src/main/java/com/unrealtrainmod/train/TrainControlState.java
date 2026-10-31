/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

/** Normalized, consist-wide inputs. Direction is relative to the reference member's front. */
public record TrainControlState(Reverser reverser, double throttle, double trainBrake, boolean emergencyBrake) {
    public enum Reverser {
        FORWARD(1), NEUTRAL(0), REVERSE(-1);
        private final int direction;
        Reverser(int direction) { this.direction = direction; }
        public int direction() { return direction; }
        public Reverser next() { return values()[(ordinal() + 1) % values().length]; }
        /** Conversion between the consist frame and a member's own facing is its own inverse. */
        public Reverser inFrame(int memberDirection) {
            return memberDirection > 0 || this == NEUTRAL ? this : this == FORWARD ? REVERSE : FORWARD;
        }
    }
}
