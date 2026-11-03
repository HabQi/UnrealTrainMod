/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.resource.PackageAsset;

/** Per-runtime render snapshot. Never retains a native entity or its world. */
public final class RtmVehicleView {
    private final PackageAsset script;
    private int tick, light;
    private float speed, wheelRotation, doorLeft, doorRight;
    private int notch, trainDirection, doorState;

    RtmVehicleView(PackageAsset script) { this.script = script; }

    void update(int tick, int light, float speed, int notch, int trainDirection, int doorState,
                float wheelRotation, float doorLeft, float doorRight) {
        this.tick = tick;
        this.light = light;
        this.speed = speed;
        this.notch = notch;
        this.trainDirection = trainDirection;
        this.doorState = doorState;
        this.wheelRotation = wheelRotation;
        this.doorLeft = doorLeft;
        this.doorRight = doorRight;
    }

    int tick() { return tick; }
    float wheelRotation() { return wheelRotation; }
    float doorLeft() { return doorLeft; }
    float doorRight() { return doorRight; }

    /** Signed blocks/tick relative to the owning vehicle's front; multiply by 72 for km/h. */
    public float getSpeed() { return speed; }

    public int getNotch() { return notch; }

    /** Legacy two-way selector: 0 = front (also native neutral), 1 = back. */
    public int getTrainDirection() { return trainDirection; }

    public byte getVehicleState(RtmTrainState.TrainStateType type) {
        return switch (type) {
            case State_TrainDir -> (byte)trainDirection;
            case State_Notch -> (byte)notch;
            case State_Door -> (byte)doorState;
            case State_Announcement -> {
                ScriptDiagnostics.temporaryAnnouncement(script);
                yield 1;
            }
        };
    }

    /** Obfuscated 1.12.2 packed-light query used by legacy interior-light scripts. */
    public int func_70070_b() { return light; }
}
