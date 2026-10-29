/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class TrainEntityRenderer extends EntityRenderer<TrainEntity> {
    public TrainEntityRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void render(TrainEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        var model = ClientModelManager.INSTANCE.model(entity.getModelId());
        var vehiclePose = entity.renderPose(partialTick);
        pose.pushPose();
        try {
            if (vehiclePose != null) pose.mulPose(vehiclePose.body().rotation());
            else {
                pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot())));
                pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partialTick, entity.xRotO, entity.getXRot())));
            }
            if (model == null) {
                var definition = VehicleCatalog.find(entity.getModelId());
                if (vehiclePose != null && definition != null) {
                    for (var part : definition.parts()) {
                        pose.pushPose();
                        try {
                            var transform = vehiclePose.partTransform(part);
                            var p = transform.position();
                            pose.translate(p.x(), p.y(), p.z());
                            pose.mulPose(transform.rotation());
                            lineBox(part.bounds(), pose, buffers);
                        } finally {
                            pose.popPose();
                        }
                    }
                } else lineBox(definition == null ? ModelBounds.MISSING : definition.bounds(), pose, buffers);
            } else {
                if (vehiclePose == null) TrainModelRenderer.render(model, pose, buffers, light);
                else TrainModelRenderer.render(model, vehiclePose, pose, light);
            }
        } finally {
            pose.popPose();
        }
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    private static void lineBox(ModelBounds bounds, PoseStack pose, MultiBufferSource buffers) {
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
                bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), 1, 0, 1, 1);
    }

    @Override public ResourceLocation getTextureLocation(TrainEntity entity) { return ClientModelManager.WHITE; }
}
