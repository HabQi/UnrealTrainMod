/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.sound.VehicleSoundSequence;
import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Tick-driven playback. Event counters preserve short pulses without replaying historical events. */
final class SoundSequencePlayer {
    private enum Phase { IDLE, START, MAIN, STOP, DONE }
    private static final TrackVector ORIGIN = new TrackVector(0, 0, 0);
    private final VehicleSoundSequence definition;
    private final TrackVector offset;
    private Phase phase = Phase.IDLE;
    private boolean observed, active, audible, oneShot;
    private long previousCounter;
    private boolean prepared;
    private PackageSoundLibrary.Clip start, main, stop;
    private PackageSoundInstance voice;
    private double remaining;

    SoundSequencePlayer(VehicleSoundSequence definition) { this(definition, ORIGIN); }

    SoundSequencePlayer(VehicleSoundSequence definition, TrackVector offset) {
        this.definition = definition;
        this.offset = offset;
    }

    void update(TrainEntity entity, boolean requested, float volume, float pitch, double distanceSquared,
                boolean enabled) {
        update(entity, requested, 0, false, false, volume, pitch, distanceSquared, enabled);
    }

    /** The server distinguishes held controls from auto-released pulses, even across multiple snapshots. */
    void updateTriggered(TrainEntity entity, boolean requested, long counter, boolean pulse, float volume, float pitch,
                         double distanceSquared, boolean enabled) {
        update(entity, requested, counter, false, pulse, volume, pitch, distanceSquared, enabled);
    }

    void updateEvent(TrainEntity entity, long counter, Vec3 listener, boolean enabled) {
        update(entity, false, counter, true, true, 1, 1,
                PackageSoundInstance.position(entity, offset).distanceToSqr(listener), enabled);
    }

    private void update(TrainEntity entity, boolean requested, long counter, boolean eventOnly, boolean pulse,
                        float volume, float pitch, double distanceSquared, boolean enabled) {
        if (definition == null) return;
        boolean withinRange = enabled && definition.volume() > 0
                && distanceSquared < (double)definition.distance() * definition.distance();
        boolean changed = observed && requested != active;
        boolean resumed = !observed || !audible;
        boolean triggered = !resumed && counter > previousCounter;
        observed = true;
        previousCounter = counter;
        active = requested;
        audible = withinRange;
        if (!withinRange) {
            oneShot = false;
            phase(Phase.IDLE);
            return;
        }
        if (resumed) {
            // A newly observed/ranged-in vehicle only resumes an already sustained loop.
            oneShot = false;
            phase(requested && !pulse && definition.looping() ? Phase.MAIN : Phase.IDLE);
        } else if (triggered) {
            oneShot = eventOnly || pulse || !requested;
            phase(Phase.START);
        } else if (changed && !eventOnly && !pulse && !oneShot) {
            phase(requested ? Phase.START : Phase.STOP);
        }
        if (voice != null) {
            voice.update(volume * definition.volume(), pitch);
            if (phase != Phase.MAIN || oneShot || !definition.looping()) {
                remaining -= pitch;
                if (remaining <= 0) phase(next());
            }
        }
        if (voice != null || phase == Phase.IDLE || phase == Phase.DONE) return;
        if (!prepared) {
            start = clip(definition.start());
            main = clip(definition.main());
            stop = clip(definition.stop());
            prepared = true;
        }
        // Skip absent/failed segments without delaying the next segment or retaining a failed event.
        while (phase != Phase.DONE) {
            var clip = switch (phase) {
                case START -> start;
                case MAIN -> main;
                case STOP -> stop;
                default -> null;
            };
            if (clip == null || clip.decoded().isCompletedExceptionally()) {
                phase(next());
                continue;
            }
            var decoded = clip.ready();
            if (decoded == null) return;
            boolean loop = phase == Phase.MAIN && !oneShot && definition.looping();
            voice = new PackageSoundInstance(entity, clip, loop, definition.distance(), volume * definition.volume(), pitch, offset);
            remaining = decoded.ticks();
            Minecraft.getInstance().getSoundManager().play(voice);
            return;
        }
    }

    private Phase next() {
        return switch (phase) {
            case START -> Phase.MAIN;
            case MAIN -> oneShot ? Phase.STOP : Phase.DONE;
            default -> Phase.DONE;
        };
    }

    private void phase(Phase next) {
        if (voice != null) voice.close();
        voice = null;
        phase = next;
        if (next == Phase.DONE) oneShot = false;
    }

    private static PackageSoundLibrary.Clip clip(PackageAsset asset) {
        return asset == null ? null : PackageSoundLibrary.clip(asset);
    }

    void close() { oneShot = false; phase(Phase.IDLE); }
}
