/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.sound.VehicleSoundSequence;
import com.unrealtrainmod.vehicle.VehicleRuntimeState;
import java.util.Map;

/** Configuration order identifies a playback instance; the referenced frame data can be shared. */
public record IrAnimationDefinition(PackageAsset asset, String controlGroup, IrReadout readout, Mode mode,
                                    float framesPerTick, float rangeMin, float rangeMax, float offset, boolean invert,
                                    VehicleSoundSequence sound) {
    public enum Mode { VALUE, LOOP, PLAY_BOTH, PLAY_FORWARD }

    public float value(IrVehicleDefinition definition, Map<String, Float> controls, VehicleRuntimeState state,
                       boolean front, boolean rear) {
        float raw = controlGroup != null ? definition.value(controlGroup, controls, state, front, rear)
                : readout.sample(state, definition.maxSpeedKmh(), front, rear);
        float value = Math.clamp((raw - rangeMin) / (rangeMax - rangeMin), 0, 1) + offset;
        return invert ? 1 - value : value;
    }
}
