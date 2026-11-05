/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

/** Client game-tick timeline; resource lifetimes never reset this state. */
final class TurnoutAnimation {
    private float start, target;
    private double startTime, duration;

    TurnoutAnimation(float position) { start = target = position; }

    float position(double time) {
        if (duration == 0) return target;
        float fraction = (float)Math.clamp((time - startTime) / duration, 0, 1);
        return start + (target - start) * fraction;
    }

    void target(float value, double time, double seconds) {
        if (value == target) return;
        start = position(time);
        target = value;
        startTime = time;
        duration = Math.abs(target - start) * seconds * 20;
    }

    void finish() { start = target; duration = 0; }
    boolean instant() { return duration == 0; }
    boolean moving(double time) { return position(time) != target; }
}
