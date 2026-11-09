/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehiclePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** One moving voice; all voices for the same clip share its decoded static buffer. */
final class PackageSoundInstance extends AbstractTickableSoundInstance {
    private final TrainEntity entity;
    private final long generation = PackageSoundLibrary.generation();
    private final WeighedSoundEvents event;
    private final TrackVector offset;
    private float baseVolume;

    PackageSoundInstance(TrainEntity entity, PackageSoundLibrary.Clip clip, boolean loop, int distance,
                          float volume, float pitch) {
        this(entity, clip, loop, distance, volume, pitch, new TrackVector(0, 0, 0));
    }

    PackageSoundInstance(TrainEntity entity, PackageSoundLibrary.Clip clip, boolean loop, int distance,
                          float volume, float pitch, TrackVector offset) {
        super(SoundEvent.createVariableRangeEvent(clip.id()), SoundSource.NEUTRAL, RandomSource.create());
        this.entity = entity;
        this.offset = offset;
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
        baseVolume = Math.clamp(volume, 0, 1);
        this.volume = baseVolume * ridingVolume();
        this.pitch = pitch;
    }

    private static float ridingVolume() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.options.getCameraType().isFirstPerson()) return 1;
        var camera = mc.getCameraEntity();
        if (camera == null || !(camera.getVehicle() instanceof TrainEntity train)) return 1;
        var model = VehicleCatalog.find(train.getModelId());
        return model == null ? 1 : model.ir().sounds().ridingVolume();
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
        volume = baseVolume * ridingVolume();
        var position = position(entity, offset);
        x = position.x; y = position.y; z = position.z;
    }

    static Vec3 position(TrainEntity entity, TrackVector offset) {
        var pose = entity.renderPose(1);
        var origin = pose == null ? new TrackVector(entity.getX(), entity.getY(), entity.getZ()) : pose.body().position();
        if (offset.x() == 0 && offset.y() == 0 && offset.z() == 0) return new Vec3(origin.x(), origin.y(), origin.z());
        var rotation = pose == null ? VehiclePose.Body.rotation(entity.getYRot(), entity.getXRot()) : pose.body().rotation();
        var local = new Vector3f((float)offset.x(), (float)offset.y(), (float)offset.z()).rotate(rotation);
        return new Vec3(origin.x() + local.x, origin.y() + local.y, origin.z() + local.z);
    }

    void close() {
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
    }
}
