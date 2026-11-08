/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/** One moving voice; all voices for the same clip share its decoded static buffer. */
final class PackageSoundInstance extends AbstractTickableSoundInstance {
    private final TrainEntity entity;
    private final long generation = PackageSoundLibrary.generation();
    private final WeighedSoundEvents event;

    PackageSoundInstance(TrainEntity entity, PackageSoundLibrary.Clip clip, boolean loop, int distance,
                          float volume, float pitch) {
        super(SoundEvent.createVariableRangeEvent(clip.id()), SoundSource.NEUTRAL, RandomSource.create());
        this.entity = entity;
        this.looping = loop;
        this.sound = new Sound(clip.id(), ConstantFloat.of(1), ConstantFloat.of(1), 1,
                Sound.Type.FILE, false, false, distance);
        event = new WeighedSoundEvents(clip.id(), null);
        event.addSound(sound);
        update(volume, pitch);
        tick();
    }

    @Override public WeighedSoundEvents resolve(SoundManager manager) { return event; }

    void update(float volume, float pitch) {
        // Vanilla multiplies attenuation distance by volume above one; keep the authored radius fixed.
        this.volume = Math.clamp(volume, 0, 1);
        this.pitch = pitch;
    }

    @Override public boolean canPlaySound() {
        return !isStopped() && !entity.isRemoved() && entity.level() == Minecraft.getInstance().level
                && generation == PackageSoundLibrary.generation();
    }

    @Override public boolean canStartSilent() { return true; }

    @Override public void tick() {
        if (!canPlaySound()) {
            stop();
            return;
        }
        var pose = entity.renderPose(1);
        TrackVector position = pose == null ? new TrackVector(entity.getX(), entity.getY(), entity.getZ())
                : pose.body().position();
        x = position.x(); y = position.y(); z = position.z();
    }

    void close() {
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
    }
}
