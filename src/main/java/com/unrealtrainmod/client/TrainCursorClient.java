/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.unrealtrainmod.client.gui.TrainDrivingOverlay;
import com.unrealtrainmod.client.gui.TrainIrControlPanel;
import com.unrealtrainmod.client.gui.TrainIrControlScreen;
import com.unrealtrainmod.compat.ir.IrInteractionRules;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.lwjgl.glfw.GLFW;

/** The single owner of the free cursor and input routing for both the HUD and model controls. */
public final class TrainCursorClient {
    private static final IKeyConflictContext CONTEXT = new IKeyConflictContext() {
        @Override public boolean isActive() { return eligible(); }
        @Override public boolean conflicts(IKeyConflictContext other) { return other == this || other == KeyConflictContext.IN_GAME; }
    };
    private static final KeyMapping CURSOR = new KeyMapping("key.unrealtrainmod.driving.cursor", CONTEXT,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "key.categories.unrealtrainmod.driving");
    private static boolean cursorOwned, contextKnown;
    private static Entity mount;
    private static UUID driverSession;

    private TrainCursorClient() {}
    public static void registerKey(RegisterKeyMappingsEvent event) { event.register(CURSOR); }

    private static boolean eligible() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null && mc.player.isAlive() && !mc.player.isSpectator()
                && (mc.screen == null || mc.screen instanceof TrainIrControlScreen) && mc.getOverlay() == null
                && mc.isWindowActive() && !mc.isPaused()
                && (TrainDrivingClient.consoleVisible() || mc.options.getCameraType().isFirstPerson() && IrInteractionRules.holdingKey(mc.player));
    }

    public static boolean usingCursor() {
        return eligible() && (cursorOwned || Minecraft.getInstance().screen instanceof TrainIrControlScreen);
    }

    public static boolean modelInput() { return usingCursor() && CURSOR.isDown(); }

    public static void update() {
        var mc = Minecraft.getInstance();
        if (!eligible()) CURSOR.setDown(false);
        boolean requested = eligible() && CURSOR.isDown();
        if (!TrainDrivingClient.consoleVisible()) TrainDrivingOverlay.release();
        if (mc.screen instanceof TrainIrControlScreen screen) {
            cursorOwned = false;
            if (eligible() && TrainDrivingClient.consoleVisible() && TrainIrControlPanel.expanded()
                    && (requested || screen.searching())) return;
            screen.closeInput();
        }
        if (requested && TrainDrivingClient.consoleVisible() && TrainIrControlPanel.expanded() && mc.screen == null) {
            release();
            mc.setScreen(new TrainIrControlScreen());
            // Opening a Screen clears mappings, including the cursor key that is still held.
            CURSOR.setDown(true);
            cursorOwned = false;
            return;
        }
        if (!requested) release();
        if (requested && !cursorOwned && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            cursorOwned = true;
            for (var key : new KeyMapping[] {mc.options.keyAttack, mc.options.keyUse, mc.options.keyPickItem}) {
                key.setDown(false);
                while (key.consumeClick()) { }
            }
        } else if (!requested && cursorOwned) {
            // Screens own their cursor. grabMouse must never close one on their behalf.
            if (mc.screen != null || mc.level == null || mc.player == null) cursorOwned = false;
            else if (mc.getOverlay() == null && mc.isWindowActive()) {
                mc.mouseHandler.grabMouse();
                cursorOwned = false;
            }
        }
    }

    public static boolean key(int key, int scanCode, boolean down) {
        if (!CURSOR.matches(key, scanCode)) return false;
        CURSOR.setDown(down);
        update();
        return true;
    }

    public static boolean mouseKey(int button, boolean down) {
        if (!CURSOR.matchesMouse(button)) return false;
        CURSOR.setDown(down);
        update();
        return true;
    }

    public static boolean keyEvent(InputEvent.Key event) {
        if (Minecraft.getInstance().screen instanceof TrainIrControlScreen) return false;
        if (key(event.getKey(), event.getScanCode(), event.getAction() != GLFW.GLFW_RELEASE)) return true;
        update();
        return false;
    }

    public static boolean mouseEvent(InputEvent.MouseButton.Pre event) {
        if (Minecraft.getInstance().screen instanceof TrainIrControlScreen) return false;
        if (eligible() && mouseKey(event.getButton(), event.getAction() != GLFW.GLFW_RELEASE)) {
            event.setCanceled(true);
            return true;
        }
        update();
        if (!usingCursor()) return false;
        event.setCanceled(true);
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (event.getAction() == GLFW.GLFW_PRESS) click(mouseX(), mouseY());
            else if (event.getAction() == GLFW.GLFW_RELEASE) { drag(mouseX(), mouseY()); release(); }
        }
        return true;
    }

    public static boolean scrollEvent(InputEvent.MouseScrollingEvent event) {
        update();
        if (Minecraft.getInstance().screen instanceof TrainIrControlScreen || !usingCursor()) return false;
        event.setCanceled(true);
        scroll(mouseX(), mouseY(), event.getScrollDeltaY());
        return true;
    }

    public static void click(double x, double y) {
        if (!usingCursor()) return;
        release();
        if (TrainDrivingOverlay.contains(x, y)) TrainDrivingOverlay.click(x, y);
        else IrControlInteraction.click(x, y);
    }

    public static void drag(double x, double y) {
        if (!usingCursor()) return;
        TrainIrControlPanel.drag(x, y);
        TrainDrivingOverlay.drag(y);
        IrControlInteraction.drag(x, y);
    }

    public static void scroll(double x, double y, double delta) {
        if (!usingCursor()) return;
        if (TrainDrivingOverlay.contains(x, y)) TrainDrivingOverlay.scroll(x, y, delta);
        else IrControlInteraction.scroll(x, y, delta);
    }

    public static void release() { IrControlInteraction.release(); TrainDrivingOverlay.release(); }

    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var state = TrainDrivingClient.state();
        var nextMount = mc.player == null ? null : mc.player.getVehicle();
        var nextDriver = state == null ? null : state.session();
        if (contextKnown && (mount != nextMount || !Objects.equals(driverSession, nextDriver))) {
            release();
            IrControlInteraction.reset();
        }
        contextKnown = true; mount = nextMount; driverSession = nextDriver;
        update();
        if (usingCursor()) drag(mouseX(), mouseY());
        IrControlInteraction.tick();
        while (CURSOR.consumeClick()) { }
    }

    public static void openingScreen(ScreenEvent.Opening event) {
        if (event.getNewScreen() != null && !(event.getNewScreen() instanceof TrainIrControlScreen)) {
            release(); CURSOR.setDown(false);
        }
    }

    public static void render(RenderGuiEvent.Post event) { IrControlInteraction.render(event.getGuiGraphics()); }

    private static void reset() {
        CURSOR.setDown(false);
        release(); IrControlInteraction.reset();
        cursorOwned = contextKnown = false; mount = null; driverSession = null;
    }
    public static void unload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) reset(); }
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { reset(); }
    public static void clonePlayer(ClientPlayerNetworkEvent.Clone event) { reset(); }

    public static double mouseX() {
        var mc = Minecraft.getInstance();
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
    }
    public static double mouseY() {
        var mc = Minecraft.getInstance();
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
    }
}
