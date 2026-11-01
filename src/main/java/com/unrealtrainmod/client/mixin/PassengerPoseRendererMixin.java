/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.train.PassengerPose;
import com.unrealtrainmod.train.TrainPassengerClientState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies posture as a purely visual offset of the passenger being rendered. The ride relation, the
 * synced personal point and the vehicle wide sitting flag stay untouched, so drivers, other
 * passengers and any later render keep vanilla behaviour.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class PassengerPoseRendererMixin {
    @Unique private static final float SITTING_DROP = -0.2F;
    /** Half of the head height in model space, in blocks. */
    @Unique private static final float HEAD_RADIUS = 0.5F;

    @Shadow
    protected EntityModel<?> model;

    @Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;shouldRiderSit()Z"))
    private boolean unrealtrainmod$shouldSit(Entity vehicle, LivingEntity entity, float entityYaw,
                                             float partialTick, PoseStack poseStack,
                                             MultiBufferSource buffer, int packedLight) {
        var state = poseState(entity);
        return state == null ? vehicle.shouldRiderSit() : state.pose == PassengerPose.SITTING;
    }

    @Inject(method = "setupRotations", at = @At("HEAD"), cancellable = true)
    private void unrealtrainmod$posture(LivingEntity entity, PoseStack poseStack, float bob, float yBodyRot,
                                        float partialTick, float scale, CallbackInfo callback) {
        var state = poseState(entity);
        if (state == null) {
            return;
        }
        if (state.pose == PassengerPose.SITTING) {
            poseStack.translate(0.0F, SITTING_DROP, 0.0F);
            return;
        }
        if (state.pose != PassengerPose.LYING) {
            return;
        }
        var body = ((TrainEntity) entity.getVehicle()).renderBody(partialTick);
        if (body == null) {
            return;
        }
        // Head toward the car's local right and face up. The flip about the horizontal axes done by
        // the caller's scale(-1,-1,1) is applied before this rotation, so the matrix below maps the
        // model axes onto the car frame as head -> -X, face -> +Y, feet -> +X.
        var lying = lyingRotation();
        poseStack.mulPose(body.rotation());
        poseStack.translate(HEAD_RADIUS * 0.5F, state.heightStep * PassengerPose.STEP_HEIGHT, 0.0F);
        poseStack.mulPose(lying);
        callback.cancel();
    }

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER))
    private void unrealtrainmod$flatten(LivingEntity entity, float entityYaw, float partialTick,
                                        PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                        CallbackInfo callback) {
        var state = poseState(entity);
        if (state == null || state.pose != PassengerPose.LYING) {
            return;
        }
        if (!(this.model instanceof HumanoidModel<?> humanoid)) {
            return;
        }
        flat(humanoid.head);
        flat(humanoid.hat);
        flat(humanoid.rightArm);
        flat(humanoid.leftArm);
        flat(humanoid.rightLeg);
        flat(humanoid.leftLeg);
        if (this.model instanceof PlayerModel<?> player) {
            // The outer skin layers are siblings of the limbs and were copied from them by setupAnim.
            flat(player.rightSleeve);
            flat(player.leftSleeve);
            flat(player.rightPants);
            flat(player.leftPants);
        }
    }

    private static Quaternionf lyingRotation() {
        return new Quaternionf().rotationY((float) (Math.PI / 2.0)).rotateX((float) (-Math.PI / 2.0));
    }

    private static void flat(ModelPart part) {
        part.xRot = 0.0F;
        part.yRot = 0.0F;
        part.zRot = 0.0F;
    }

    /** Synced posture of the rendered player, or null when it is not an ordinary passenger of that car. */
    private static TrainPassengerClientState.Entry poseState(LivingEntity entity) {
        if (!(entity instanceof Player)) {
            return null;
        }
        var state = TrainPassengerClientState.entry(entity.getUUID());
        if (state == null || !(entity.getVehicle() instanceof TrainEntity train)) {
            return null;
        }
        return train.getUUID().equals(state.vehicle) ? state : null;
    }
}
