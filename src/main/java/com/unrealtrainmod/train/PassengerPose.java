/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

/**
 * Posture of an ordinary passenger inside a car. Posture and lying height belong to the passenger
 * session only: they are never written to NBT and disappear together with the session.
 */
public enum PassengerPose {
    STANDING, SITTING, LYING;

    public static final int LYING_MIN_HEIGHT_STEP = 3;
    public static final int MAX_HEIGHT_STEP = 26;
    /** Visual height of one lying step, in blocks. */
    public static final float STEP_HEIGHT = 0.1F;

    public static PassengerPose byId(int id) {
        var poses = values();
        return id >= 0 && id < poses.length ? poses[id] : STANDING;
    }
}
