/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.sound.VehicleSoundSequence;
import net.minecraft.client.Minecraft;

/** Tick-driven playback; asynchronous decoding never starts a voice by itself. */
final class SoundSequencePlayer {
    private enum Phase { IDLE, START, MAIN, STOP, DONE }

    private final VehicleSoundSequence definition;
    private Phase phase = Phase.IDLE;
    private boolean observed, active, audible;
    private boolean prepared;
    private PackageSoundLibrary.Clip start, main, stop;
    private PackageSoundInstance voice;
    private double remaining;

    SoundSequencePlayer(VehicleSoundSequence definition) { this.definition = definition; }

    void update(TrainEntity entity, boolean requested, float volume, float pitch, double distanceSquared,
                boolean enabled) {
        if (definition == null) return;
        boolean withinRange = enabled && definition.volume() > 0
                && distanceSquared < (double)definition.distance() * definition.distance();
        boolean changed = observed && requested != active;
        boolean resumed = !observed || !audible;
        observed = true;
        active = requested;
        audible = withinRange;
        if (!withinRange) {
            phase(Phase.IDLE);
            return;
        }
        if (resumed) {
            // Joining a tracked/ranged-in vehicle must not replay its historical edge sounds.
            phase(requested && definition.looping() ? Phase.MAIN : Phase.IDLE);
        } else if (changed) {
            phase(requested ? definition.start() == null ? Phase.MAIN : Phase.START : Phase.STOP);
        }

        if (voice != null) {
            voice.update(volume * definition.volume(), pitch);
            if (phase != Phase.MAIN || !definition.looping()) {
                remaining -= pitch;
                if (remaining <= 0) phase(phase == Phase.START ? Phase.MAIN : Phase.DONE);
            }
        }
        if (voice != null || phase == Phase.IDLE || phase == Phase.DONE) return;
        if (!prepared) {
            // Decode all segments together so changing phases does not initiate another ZIP read.
            start = clip(definition.start());
            main = clip(definition.main());
            stop = clip(definition.stop());
            prepared = true;
        }
        var clip = switch (phase) {
            case START -> start;
            case MAIN -> main;
            case STOP -> stop;
            default -> null;
        };
        if (clip == null) {
            phase(Phase.DONE);
            return;
        }
        var decoded = clip.ready();
        if (decoded == null) return;
        boolean loop = phase == Phase.MAIN && definition.looping();
        voice = new PackageSoundInstance(entity, clip, loop, definition.distance(), volume * definition.volume(), pitch);
        remaining = decoded.ticks();
        Minecraft.getInstance().getSoundManager().play(voice);
    }

    private void phase(Phase next) {
        if (voice != null) voice.close();
        voice = null;
        phase = next;
    }

    private static PackageSoundLibrary.Clip clip(PackageAsset asset) {
        return asset == null ? null : PackageSoundLibrary.clip(asset);
    }

    void close() { phase(Phase.IDLE); }
}
