/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import com.unrealtrainmod.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

public final class ActivationWarningOverlay {
    private ActivationWarningOverlay() {}

    public static void render(RenderGuiEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.options.hideGui
                || !ClientConfig.SHOW_ACTIVATION_WARNING.getAsBoolean()) return;

        int color = (minecraft.gui.getGuiTicks() / 10) % 2 == 0 ? 0xFFFFFF00 : 0xFFFF0000;
        var graphics = event.getGuiGraphics();
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.scale(2.5f, 2.5f, 1.0f);
            graphics.drawString(minecraft.font, Component.translatable("gui.warning"), 2, 5, color, true);
        } finally {
            pose.popPose();
        }
    }
}
