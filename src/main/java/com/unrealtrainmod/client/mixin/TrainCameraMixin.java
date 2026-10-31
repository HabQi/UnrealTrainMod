/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.mixin;

import com.unrealtrainmod.entity.TrainEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.joml.Vector3f;

/** Anchor the local riding camera to the rendered train, before vanilla third-person collision checks. */
@Mixin(Camera.class)
public abstract class TrainCameraMixin {
    @Shadow public abstract Vec3 getPosition();
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow public abstract float getYRot();
    @Shadow public abstract float getXRot();
    @Shadow public abstract float getRoll();
    @Shadow protected abstract void setRotation(float yaw, float pitch, float roll);

    @Unique private TrainEntity unrealtrainmod$vehicle;
    @Unique private float unrealtrainmod$mountYaw;

    @Inject(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", shift = At.Shift.AFTER))
    private void unrealtrainmod$alignSeat(BlockGetter level, Entity entity, boolean detached,
                                         boolean thirdPersonReverse, float partialTick, CallbackInfo callback) {
        if (entity != Minecraft.getInstance().player || !(entity.getVehicle() instanceof TrainEntity train)) {
            unrealtrainmod$vehicle = null;
            return;
        }
        var body = train.renderBody(partialTick);
        if (body == null) {
            unrealtrainmod$vehicle = null;
            return;
        }
        if (unrealtrainmod$vehicle != train) {
            unrealtrainmod$vehicle = train;
            unrealtrainmod$mountYaw = body.yaw();
        }
        var bodyRotation = body.rotation();
        // Preserve vanilla eye-height smoothing, but place the eyes along the car's own up axis.
        var eyeOffset = getPosition().subtract(entity.getPosition(partialTick));
        var eyes = bodyRotation.transform(new Vector3f((float) eyeOffset.x, (float) eyeOffset.y, (float) eyeOffset.z));
        setPosition(train.passengerRidingPosition(body).add(eyes.x, eyes.y, eyes.z));

        // Mouse angles remain cabin-relative; compose rotations so looking sideways on a slope
        // also follows the body correctly rather than merely adding world-space pitch angles.
        float radians = (float) (Math.PI / 180);
        float relativeYaw = Mth.wrapDegrees(getYRot() - unrealtrainmod$mountYaw);
        var angles = bodyRotation.rotateY((float) Math.PI - relativeYaw * radians)
                .rotateX(-getXRot() * radians).rotateZ(-getRoll() * radians)
                .getEulerAnglesYXZ(new Vector3f());
        setRotation(180 - angles.y / radians, -angles.x / radians, -angles.z / radians);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void unrealtrainmod$clearVehicle(CallbackInfo callback) {
        unrealtrainmod$vehicle = null;
    }
}
