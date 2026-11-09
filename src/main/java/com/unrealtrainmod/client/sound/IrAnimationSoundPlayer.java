/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.compat.ir.IrAnimationDefinition;
import com.unrealtrainmod.compat.ir.IrVehicleSnapshot;
import com.unrealtrainmod.entity.TrainEntity;
import java.util.concurrent.CompletableFuture;

/** One distinct animation sound, independent of render visibility and GPU model preparation. */
final class IrAnimationSoundPlayer {
    private final IrAnimationDefinition definition;
    private final int index;
    private final SoundSequencePlayer sequence;
    private CompletableFuture<Integer> frames;
    private boolean observed, audible, pendingEvent;
    private long previousEvents;

    IrAnimationSoundPlayer(IrAnimationDefinition definition, int index) {
        this.definition = definition;
        this.index = index;
        sequence = new SoundSequencePlayer(definition.sound());
    }

    void update(TrainEntity entity, double distanceSquared, boolean enabled) {
        var clocks = entity.irSnapshot().clocks();
        var clock = index < clocks.size() ? clocks.get(index) : IrVehicleSnapshot.Clock.SETTLED;
        boolean withinRange = enabled && definition.sound().volume() > 0
                && distanceSquared < (double)definition.sound().distance() * definition.sound().distance();
        if (!withinRange || !audible) pendingEvent = false;
        if (observed && audible && withinRange && clock.soundEvents() > previousEvents) pendingEvent = true;
        observed = true; audible = withinRange; previousEvents = clock.soundEvents();
        boolean timed = definition.mode() == IrAnimationDefinition.Mode.PLAY_FORWARD
                || definition.mode() == IrAnimationDefinition.Mode.PLAY_BOTH;
        int count = 1;
        boolean ready = true;
        if (timed) {
            if (frames == null && withinRange) {
                frames = AnimationSoundTimings.frames(definition.asset());
            }
            ready = frames != null && frames.isDone() && !frames.isCompletedExceptionally();
            if (ready) count = frames.join();
        }
        double time = entity.level().getGameTime();
        boolean active = ready && definition.soundRunning(clock, count, time);
        // Disabled/pending updates still establish counter baselines, preventing deferred historical playback.
        sequence.updateAnimation(entity, active, clock.soundEvents(), ready && pendingEvent, distanceSquared, enabled && ready);
        if (ready) pendingEvent = false;
    }

    void close() { sequence.close(); frames = null; observed = audible = pendingEvent = false; }
}
