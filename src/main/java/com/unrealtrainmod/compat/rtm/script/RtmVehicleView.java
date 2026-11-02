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

    RtmVehicleView(PackageAsset script) { this.script = script; }

    void update(int tick, int light) {
        this.tick = tick;
        this.light = light;
    }

    int tick() { return tick; }

    public byte getVehicleState(RtmTrainState.TrainStateType type) {
        if (type != RtmTrainState.TrainStateType.State_Announcement) {
            throw new UnsupportedOperationException("Unsupported RTM vehicle state " + type);
        }
        ScriptDiagnostics.temporaryAnnouncement(script);
        return 1;
    }

    /** Obfuscated 1.12.2 packed-light query used by legacy interior-light scripts. */
    public int func_70070_b() { return light; }
}
