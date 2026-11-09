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
    public static final int MOVEMENT_TICKS = 3;

    public float progress(int frameCount, IrVehicleSnapshot.Clock clock, double time) {
        return clock == null || clock.tick() == Long.MIN_VALUE ? 1
                : (float)Math.clamp((time - clock.tick()) * framesPerTick / frameCount, 0, 1);
    }

    /** Shared by the visual and audio timelines; previews remain at their settled endpoints. */
    public float percentage(float value, IrVehicleSnapshot.Clock clock, int frameCount, double time, boolean live) {
        if (mode == Mode.VALUE) return value;
        if (mode == Mode.LOOP) return live && value >= 0.95f ? (float)((time * framesPerTick / frameCount) % 1) : 0;
        boolean active = clock == null ? value >= 0.95f : clock.active();
        float progress = live ? progress(frameCount, clock, time) : 1;
        return mode == Mode.PLAY_FORWARD ? (active ? progress : 0) : (active ? progress : 1 - progress);
    }

    public boolean soundRunning(IrVehicleSnapshot.Clock clock, int frameCount, double time) {
        return switch (mode) {
            case VALUE -> clock.soundTick() != Long.MIN_VALUE && time >= clock.soundTick()
                    && time - clock.soundTick() < MOVEMENT_TICKS;
            case LOOP -> clock.active();
            case PLAY_FORWARD, PLAY_BOTH -> clock.tick() != Long.MIN_VALUE && time >= clock.tick()
                    && (mode == Mode.PLAY_BOTH || clock.active()) && progress(frameCount, clock, time) < 1;
        };
    }

    public float value(IrVehicleDefinition definition, Map<String, Float> controls, VehicleRuntimeState state,
                       boolean front, boolean rear) {
        float raw = controlGroup != null ? definition.value(controlGroup, controls, state, front, rear)
                : readout.sample(state, definition.maxSpeedKmh(), front, rear);
        float value = Math.clamp((raw - rangeMin) / (rangeMax - rangeMin), 0, 1) + offset;
        return invert ? 1 - value : value;
    }
}
