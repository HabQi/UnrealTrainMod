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

public final class TrainEntityRenderer extends EntityRenderer<TrainEntity> {
    public TrainEntityRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void render(TrainEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        var model = ClientModelManager.INSTANCE.model(entity.getModelId());
        pose.pushPose();
        try {
            // The vehicle is stationary, so use the synchronized placement angles directly.
            pose.mulPose(Axis.YP.rotationDegrees(-entity.getYRot()));
            pose.mulPose(Axis.XP.rotationDegrees(entity.getXRot()));
            if (model == null) {
                var definition = VehicleCatalog.find(entity.getModelId());
                var bounds = definition == null ? ModelBounds.MISSING : definition.bounds();
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
                        bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), bounds.maxY(), bounds.maxZ(), 1, 0, 1, 1);
            } else {
                TrainModelRenderer.render(model, pose, buffers, light);
            }
        } finally {
            pose.popPose();
        }
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    @Override public ResourceLocation getTextureLocation(TrainEntity entity) { return ClientModelManager.WHITE; }
}
