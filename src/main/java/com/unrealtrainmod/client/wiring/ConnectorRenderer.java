/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.model.TextureLoader;
import com.unrealtrainmod.entity.ConnectorEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public final class ConnectorRenderer extends EntityRenderer<ConnectorEntity> {
    public ConnectorRenderer(EntityRendererProvider.Context context) { super(context); }
    @Override public void render(ConnectorEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        if (entity.node() == null) return;
        var node = WiringClient.displayed(entity.node()); var model = WiringModelManager.INSTANCE.model(node.model());
        pose.pushPose();
        try {
            var delta = node.origin().subtract(entity.position()); pose.translate(delta.x, delta.y, delta.z); pose.mulPose(node.rotation());
            if (model != null) WiringGeometry.render(model.draws(), pose, buffers, light);
            var player = Minecraft.getInstance().player;
            boolean tools = player != null && (player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())
                    || player.getMainHandItem().is(UnrealTrainMod.WIRE.get()) || player.getMainHandItem().is(UnrealTrainMod.INSULATOR.get())
                    || player.getMainHandItem().isEmpty() && player.isShiftKeyDown());
            if (model == null || model.definition().empty() && (tools || Minecraft.getInstance().screen instanceof ConnectorEditScreen)) {
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), -.125, -.125, -.125, .125, .125, .125,
                        model == null ? 1 : .2f, model == null ? 0 : 1, 1, 1);
            }
        } finally { pose.popPose(); }
    }
    @Override public ResourceLocation getTextureLocation(ConnectorEntity entity) { return TextureLoader.WHITE; }
}
