/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.compat.rtm.RtmVehicleSounds;
import com.unrealtrainmod.compat.rtm.script.RtmScriptCache;
import com.unrealtrainmod.compat.rtm.script.RtmSoundScriptRuntime;
import com.unrealtrainmod.compat.rtm.script.RtmTrainState;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.sound.VehicleSoundSequence;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Vehicle-local NJ01 sound playback, driven by client ticks and server event counters. */
final class RtmVehicleSoundPlayer {
    private static final TrackVector ORIGIN = new TrackVector(0, 0, 0);
    final TrainEntity entity;
    final RtmVehicleSounds definition;
    private final SoundSequencePlayer open, close;
    private final Map<String, Loop> loops = new HashMap<>();
    private SoundSequencePlayer announcement;
    private RtmSoundScriptRuntime script;
    private boolean scriptFailed, announcementAudible;
    private long previousAnnouncement;

    RtmVehicleSoundPlayer(TrainEntity entity, RtmVehicleSounds definition) {
        this.entity = entity;
        this.definition = definition;
        open = new SoundSequencePlayer(definition.doorOpen());
        close = new SoundSequencePlayer(definition.doorClose());
        definition.loops().forEach((id, sequence) -> loops.put(id, new Loop(sequence)));
    }

    void tick(Vec3 listener, boolean enabled, RtmScriptCache scripts) {
        double distanceSquared = PackageSoundInstance.position(entity, ORIGIN).distanceToSqr(listener);
        open.updateEvent(entity, entity.doorOpenSoundEvents(), distanceSquared, enabled);
        close.updateEvent(entity, entity.doorCloseSoundEvents(), distanceSquared, enabled);
        updateAnnouncement(distanceSquared, enabled);

        boolean canHearMovement = enabled && distanceSquared < RtmVehicleSounds.MOVEMENT_DISTANCE * RtmVehicleSounds.MOVEMENT_DISTANCE;
        if (canHearMovement) updateScript(scripts);
        for (var loop : loops.values()) {
            loop.player.update(entity, loop.volume > 0, loop.volume, loop.pitch, distanceSquared, canHearMovement);
        }
    }

    private void updateAnnouncement(double distanceSquared, boolean enabled) {
        boolean audible = enabled && distanceSquared < RtmVehicleSounds.ANNOUNCEMENT_DISTANCE * RtmVehicleSounds.ANNOUNCEMENT_DISTANCE;
        long count = entity.announcementEvents();
        if (audible && announcementAudible && count > previousAnnouncement) {
            if (announcement != null) announcement.close();
            int index = entity.announcementIndex();
            announcement = index >= 0 && index < definition.announcements().size()
                    ? new SoundSequencePlayer(definition.announcements().get(index).sound()) : null;
            if (announcement != null) announcement.startEvent(entity, count, distanceSquared, true);
        } else if (announcement != null) {
            announcement.updateEvent(entity, count, distanceSquared, enabled);
        }
        announcementAudible = audible;
        previousAnnouncement = count;
    }

    private void updateScript(RtmScriptCache scripts) {
        if (scriptFailed || loops.isEmpty()) return;
        try {
            if (script == null) script = RtmSoundScriptRuntime.request(scripts, definition.movementScript(), this::setLoop);
            if (script == null) return;
            var state = entity.runtimeState();
            script.tick((float)(Math.abs(state.speed()) * 3.6), RtmTrainState.notch(state));
        } catch (IOException | RuntimeException | LinkageError exception) {
            scriptFailed = true;
            if (script != null) script.close();
            script = null;
            for (var loop : loops.values()) {
                loop.volume = 0;
                loop.player.close();
            }
            LoggerFactory.getLogger(RtmVehicleSoundPlayer.class).warn("Cannot play RTM movement sounds for {}: {}",
                    definition.movementScript(), exception.toString());
        }
    }

    private void setLoop(String id, double volume, double pitch) {
        var loop = loops.get(id);
        if (loop == null) return;
        loop.volume = (float)Math.clamp(volume, 0, 1);
        loop.pitch = (float)Math.clamp(pitch, 0.5, 2);
    }

    void close() {
        open.close();
        close.close();
        if (announcement != null) announcement.close();
        if (script != null) script.close();
        for (var loop : loops.values()) loop.player.close();
    }

    private static final class Loop {
        private final SoundSequencePlayer player;
        private float volume, pitch = 1;

        private Loop(VehicleSoundSequence sequence) { player = new SoundSequencePlayer(sequence); }
    }
}
