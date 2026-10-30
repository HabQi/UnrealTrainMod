/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrainDebugPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

public final class TrainDebugOverlay {
    private static List<Component> lines = List.of();
    private static ResourceLocation dimension;
    private static int receivedAt;
    private TrainDebugOverlay() {}

    public static void onSync(TrackSyncEvent event) {
        if (!(event.payload() instanceof TrainDebugPayload payload)) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(payload.dimension())) return;
        dimension = payload.dimension();
        lines = payload.lines();
        receivedAt = mc.gui.getGuiTicks();
    }

    public static void render(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui || lines.isEmpty()
                || !mc.level.dimension().location().equals(dimension) || mc.gui.getGuiTicks() - receivedAt > 60) return;
        var graphics = event.getGuiGraphics();
        float scale = 0.8f;
        int width = Math.min(690, (int)(graphics.guiWidth() / scale) - 16);
        int maxRows = Math.max(1, ((int)(graphics.guiHeight() / scale) - 52) / 10);
        var wrapped = new ArrayList<FormattedCharSequence>();
        for (var line : lines) wrapped.addAll(mc.font.split(line, width));
        boolean clipped = wrapped.size() > maxRows;
        int rows = Math.min(maxRows, wrapped.size());
        graphics.pose().pushPose();
        try {
            graphics.pose().scale(scale, scale, 1);
            graphics.fill(4, 36, width + 12, 42 + rows * 10, 0xB0000000);
            for (int i = 0; i < rows; i++) {
                if (clipped && i == rows - 1) {
                    graphics.drawString(mc.font, Component.translatable("gui.unrealtrainmod.train_debug.clipped"), 8, 40 + i * 10, 0xFFFFFF);
                } else graphics.drawString(mc.font, wrapped.get(i), 8, 40 + i * 10, 0xFFFFFF, false);
            }
        } finally { graphics.pose().popPose(); }
    }

    private static void clear() { lines = List.of(); dimension = null; }
    public static void onUnload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) clear(); }
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public static void onClone(ClientPlayerNetworkEvent.Clone event) { clear(); }
}
