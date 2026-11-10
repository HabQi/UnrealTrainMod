/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.payload.TrainPassengerPosePayload;
import com.unrealtrainmod.network.payload.TrainPassengerStatePayload;
import com.unrealtrainmod.train.PassengerPose;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Turns key edges into explicit posture requests for anyone walking inside a train. Posture and
 * lying height stay server owned; this class only decides when a request is allowed and how a held
 * height key repeats.
 */
public final class TrainPassengerPoseClient {
    private static final int HOLD_TICKS = 10;
    private static final int REPEAT_TICKS = 2;
    private static final KeyMapping SIT = key("sit", GLFW.GLFW_KEY_G);
    private static final KeyMapping LIE = key("lie", GLFW.GLFW_KEY_H);
    private static final KeyMapping LIE_UP = key("lie_up", GLFW.GLFW_KEY_EQUAL);
    private static final KeyMapping LIE_DOWN = key("lie_down", GLFW.GLFW_KEY_MINUS);
    private static int heightDirection;
    private static int holdTicks;
    private static boolean jumpWasDown;

    private TrainPassengerPoseClient() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.unrealtrainmod.passenger." + name, InputConstants.Type.KEYSYM, code,
                "key.categories.unrealtrainmod.passenger");
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(SIT);
        event.register(LIE);
        event.register(LIE_UP);
        event.register(LIE_DOWN);
    }

    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var state = riding(mc);
        if (state == null || mc.player == null || mc.screen != null || mc.isPaused()) {
            reset(mc);
            return;
        }
        boolean sit = consumeClicks(SIT);
        boolean lie = consumeClicks(LIE);
        boolean jumpDown = mc.options.keyJump.isDown();
        boolean jumpPressed = consumeClicks(mc.options.keyJump);
        boolean stand = (jumpDown || jumpPressed) && !jumpWasDown;
        jumpWasDown = jumpDown;
        PassengerPose requested = stand ? PassengerPose.STANDING
                : sit ? PassengerPose.SITTING : lie ? PassengerPose.LYING : null;
        if (requested != null) {
            request(state, requested, 0);
            heightDirection = 0;
            holdTicks = 0;
            return;
        }
        heightKeys(state);
    }

    private static boolean consumeClicks(KeyMapping key) {
        boolean pressed = false;
        while (key.consumeClick()) pressed = true;
        return pressed;
    }

    private static void heightKeys(TrainPassengerStatePayload state) {
        boolean up = LIE_UP.isDown();
        boolean down = LIE_DOWN.isDown();
        int direction = up == down ? 0 : up ? 1 : -1;
        if (direction == 0 || state.pose() != PassengerPose.LYING) {
            heightDirection = 0;
            holdTicks = 0;
            return;
        }
        if (direction != heightDirection) {
            heightDirection = direction;
            holdTicks = 1;
            request(state, PassengerPose.LYING, direction);
            return;
        }
        holdTicks++;
        if (holdTicks > HOLD_TICKS && (holdTicks - HOLD_TICKS - 1) % REPEAT_TICKS == 0) {
            request(state, PassengerPose.LYING, direction);
        }
    }

    private static void request(TrainPassengerStatePayload state, PassengerPose pose, int heightStepDelta) {
        PacketDistributor.sendToServer(new TrainPassengerPosePayload(state.session(), pose, heightStepDelta));
    }

    public static void render(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || mc.getOverlay() != null || riding(mc) == null) return;
        var graphics = event.getGuiGraphics();
        Component[] lines = {
            Component.translatable("gui.unrealtrainmod.passenger.help.sit", SIT.getTranslatedKeyMessage()),
            Component.translatable("gui.unrealtrainmod.passenger.help.lie", LIE.getTranslatedKeyMessage()),
            Component.translatable("gui.unrealtrainmod.passenger.help.stand", mc.options.keyJump.getTranslatedKeyMessage()),
            Component.translatable("gui.unrealtrainmod.passenger.help.lie_up", LIE_UP.getTranslatedKeyMessage()),
            Component.translatable("gui.unrealtrainmod.passenger.help.lie_down", LIE_DOWN.getTranslatedKeyMessage())
        };
        var font = mc.font;
        int[] lineWidths = new int[lines.length];
        int width = 0;
        for (int i = 0; i < lines.length; i++) {
            lineWidths[i] = font.width(lines[i]);
            width = Math.max(width, lineWidths[i]);
        }
        float guiScale = (float) mc.getWindow().getGuiScale();
        int preferredScale = Math.max(1, Math.round(0.7F * guiScale));
        int fittingScale = (int) Math.floor((graphics.guiWidth() - 8F) * guiScale / (width + 8));
        int pixelScale = Math.max(1, Math.min(preferredScale, fittingScale));
        float scale = pixelScale / guiScale;
        int lineHeight = font.lineHeight + 3;
        int height = (lines.length - 1) * lineHeight + font.lineHeight;
        float x = (Math.round((graphics.guiWidth() - 4) * guiScale) - (width + 4) * pixelScale) / guiScale;
        float y = Math.round((graphics.guiHeight() * guiScale - height * pixelScale) / 2) / guiScale;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.fill(-4, -4, width + 4, height + 4, 0x80000000);
            for (int i = 0; i < lines.length; i++) {
                graphics.drawString(font, lines[i], width - lineWidths[i], i * lineHeight, 0xFFFFFF, false);
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    private static TrainPassengerStatePayload riding(Minecraft mc) {
        var state = TrainWalkingClient.snapshot();
        if (state == null || mc.player == null) return null;
        return mc.player.getVehicle() instanceof TrainEntity train && train.getUUID().equals(state.vehicle())
                ? state : null;
    }

    private static void reset(Minecraft mc) {
        while (SIT.consumeClick()) {
        }
        while (LIE.consumeClick()) {
        }
        if (mc.player != null && mc.player.getVehicle() instanceof TrainEntity) {
            while (mc.options.keyJump.consumeClick()) {
            }
        }
        heightDirection = 0;
        holdTicks = 0;
        jumpWasDown = mc.options.keyJump.isDown();
    }

    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) reset(Minecraft.getInstance());
    }

    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset(Minecraft.getInstance());
    }

    public static void clonePlayer(ClientPlayerNetworkEvent.Clone event) {
        reset(Minecraft.getInstance());
    }
}
