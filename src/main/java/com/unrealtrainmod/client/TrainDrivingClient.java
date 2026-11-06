/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.unrealtrainmod.client.gui.TrainDrivingOverlay;
import com.unrealtrainmod.client.gui.TrainMonitorOverlay;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrainControlInputPayload;
import com.unrealtrainmod.network.payload.TrainControlInputPayload.Action;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import com.unrealtrainmod.train.TrainControlState.Reverser;
import com.unrealtrainmod.train.TrainConsist.StopReason;
import com.unrealtrainmod.vehicle.VehicleDoor;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Discrete driving requests; shared controls and motion always come from the server. */
public final class TrainDrivingClient {
    private static final String PREFIX = "gui.unrealtrainmod.driving.";
    private static final IKeyConflictContext CONTEXT = new IKeyConflictContext() {
        @Override public boolean isActive() {
            var mc = Minecraft.getInstance();
            return mc.screen == null && mc.getOverlay() == null && riding();
        }
        @Override public boolean conflicts(IKeyConflictContext other) { return other == this || other == KeyConflictContext.IN_GAME; }
    };
    private static final KeyMapping POWER = key("power", GLFW.GLFW_KEY_DOWN);
    private static final KeyMapping BRAKE = key("brake", GLFW.GLFW_KEY_UP);
    private static final KeyMapping REVERSER = key("reverser", GLFW.GLFW_KEY_R);
    private static final KeyMapping EMERGENCY = key("emergency", GLFW.GLFW_KEY_F);
    private static final KeyMapping DOOR_LEFT = key("door_left", GLFW.GLFW_KEY_J);
    private static final KeyMapping DOOR_RIGHT = key("door_right", GLFW.GLFW_KEY_K);
    private static final KeyMapping CURSOR = key("cursor", GLFW.GLFW_KEY_LEFT_ALT);
    private static TrainControlSyncPayload snapshot;
    private static long sequence;
    private static int silence, heartbeat;
    private static boolean cursorOwned;

    private TrainDrivingClient() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.unrealtrainmod.driving." + name, CONTEXT,
                InputConstants.Type.KEYSYM, code, "key.categories.unrealtrainmod.driving");
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(POWER);
        event.register(BRAKE);
        event.register(REVERSER);
        event.register(EMERGENCY);
        event.register(DOOR_LEFT);
        event.register(DOOR_RIGHT);
        event.register(CURSOR);
    }

    public static TrainEntity riddenVehicle() {
        var mc = Minecraft.getInstance();
        if (snapshot == null || mc.player == null) return null;
        return mc.player.getVehicle() instanceof TrainEntity entity && entity.getUUID().equals(snapshot.vehicle())
                ? entity : null;
    }

    private static boolean riding() {
        var mc = Minecraft.getInstance();
        return snapshot != null && mc.level != null
                && mc.level.dimension().location().equals(snapshot.dimension()) && riddenVehicle() != null;
    }

    public static TrainControlSyncPayload state() { return riding() ? snapshot : null; }

    public static boolean consoleVisible() {
        var mc = Minecraft.getInstance();
        return riding() && !mc.options.hideGui && mc.screen == null && mc.getOverlay() == null;
    }

    public static boolean usingCursor() {
        var mc = Minecraft.getInstance();
        return cursorOwned && consoleVisible() && mc.isWindowActive() && !mc.isPaused();
    }

    public static Component controlHint() {
        var mc = Minecraft.getInstance();
        return Component.translatable(PREFIX + "help", POWER.getTranslatedKeyMessage(), BRAKE.getTranslatedKeyMessage(),
                REVERSER.getTranslatedKeyMessage(), EMERGENCY.getTranslatedKeyMessage(),
                DOOR_LEFT.getTranslatedKeyMessage(), DOOR_RIGHT.getTranslatedKeyMessage(),
                mc.options.keyShift.getTranslatedKeyMessage());
    }

    private static void updateCursor() {
        var mc = Minecraft.getInstance();
        if (!consoleVisible() || !mc.isWindowActive()) CURSOR.setDown(false);
        boolean requested = consoleVisible() && mc.isWindowActive() && !mc.isPaused() && CURSOR.isDown();
        if (!requested) TrainDrivingOverlay.release();
        if (requested && !cursorOwned && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            cursorOwned = true;
            for (var key : new KeyMapping[] {mc.options.keyAttack, mc.options.keyUse, mc.options.keyPickItem}) {
                key.setDown(false);
                while (key.consumeClick()) { }
            }
        } else if (!requested && cursorOwned) {
            // A screen now owns the cursor. Never grab it here: grabMouse also closes screens.
            if (mc.screen != null || mc.level == null || mc.player == null) cursorOwned = false;
            else if (mc.getOverlay() == null && mc.isWindowActive()) {
                mc.mouseHandler.grabMouse();
                cursorOwned = false;
            }
        }
    }

    public static void onSync(TrackSyncEvent event) {
        if (!(event.payload() instanceof TrainControlSyncPayload incoming)) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(incoming.dimension())) return;
        if (!incoming.open()) {
            if (snapshot != null && snapshot.session().equals(incoming.session())) clear();
            return;
        }
        boolean newSession = snapshot == null || !snapshot.session().equals(incoming.session());
        if (newSession) {
            sequence = incoming.sequence();
            TrainDrivingOverlay.clear();
        }
        if (newSession || !snapshot.train().equals(incoming.train())) TrainMonitorOverlay.clear();
        snapshot = incoming;
        silence = 0;
        if (incoming.feedback() != TrainControlSyncPayload.Feedback.NONE && mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(PREFIX
                    + incoming.feedback().name().toLowerCase(Locale.ROOT)), true);
        }
    }

    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        updateCursor();
        if (snapshot == null || mc.isPaused()) return;
        if (mc.level == null || mc.player == null || !mc.player.isAlive()
                || !mc.level.dimension().location().equals(snapshot.dimension()) || ++silence > 60) {
            clear();
            return;
        }
        if (usingCursor()) TrainDrivingOverlay.drag(mouseY());
        for (var key : new KeyMapping[] {POWER, BRAKE, REVERSER, EMERGENCY, DOOR_LEFT, DOOR_RIGHT, CURSOR}) while (key.consumeClick()) { }
        if (riding()) TrainMonitorOverlay.tick(snapshot, silence > 20);
        if (riding() && ++heartbeat >= 10) send(Action.HEARTBEAT);
    }

    public static void keyPressed(InputEvent.Key event) {
        updateCursor();
        if (event.getAction() != GLFW.GLFW_PRESS || !CONTEXT.isActive()) return;
        if (!CURSOR.matches(event.getKey(), event.getScanCode())) controlKey(event.getKey(), event.getScanCode());
    }

    /** Raw press events distinguish deliberate input from keyboard repeat. */
    private static void controlKey(int key, int scanCode) {
        boolean power = POWER.matches(key, scanCode), brake = BRAKE.matches(key, scanCode);
        boolean direction = REVERSER.matches(key, scanCode), stop = EMERGENCY.matches(key, scanCode);
        boolean left = DOOR_LEFT.matches(key, scanCode), right = DOOR_RIGHT.matches(key, scanCode);
        press(power, brake, direction, stop);
        if (left) door(VehicleDoor.LEFT);
        if (right) door(VehicleDoor.RIGHT);
    }

    public static void mousePressed(InputEvent.MouseButton.Pre event) {
        if (!CONTEXT.isActive()) return;
        if (CURSOR.matchesMouse(event.getButton())) {
            CURSOR.setDown(event.getAction() != GLFW.GLFW_RELEASE);
            updateCursor();
            event.setCanceled(true);
            return;
        }
        if (usingCursor()) {
            // Cancel before vanilla can grab the free cursor or use a held item.
            event.setCanceled(true);
            if (event.getAction() == GLFW.GLFW_PRESS && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                TrainDrivingOverlay.click(mouseX(), mouseY());
            }
            if (event.getAction() == GLFW.GLFW_RELEASE && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                TrainDrivingOverlay.drag(mouseY());
                TrainDrivingOverlay.release();
            }
            return;
        }
        boolean power = POWER.matchesMouse(event.getButton()), brake = BRAKE.matchesMouse(event.getButton());
        boolean direction = REVERSER.matchesMouse(event.getButton()), stop = EMERGENCY.matchesMouse(event.getButton());
        boolean left = DOOR_LEFT.matchesMouse(event.getButton()), right = DOOR_RIGHT.matchesMouse(event.getButton());
        if (power || brake || direction || stop || left || right) event.setCanceled(true);
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        press(power, brake, direction, stop);
        if (left) door(VehicleDoor.LEFT);
        if (right) door(VehicleDoor.RIGHT);
    }

    public static void mouseScrolled(InputEvent.MouseScrollingEvent event) {
        if (!consoleVisible()) return;
        event.setCanceled(true);
        if (usingCursor()) TrainDrivingOverlay.scroll(mouseX(), mouseY(), event.getScrollDeltaY());
    }

    private static double mouseX() {
        var mc = Minecraft.getInstance();
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
    }

    private static double mouseY() {
        var mc = Minecraft.getInstance();
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
    }

    /** Consume actual press events, so quick taps are retained and keyboard repeat cannot change a notch. */
    private static void press(boolean power, boolean brake, boolean direction, boolean stop) {
        if (power && !brake) send(Action.HANDLE_UP);
        if (brake && !power) send(Action.HANDLE_DOWN);
        if (direction) send(Action.REVERSER_NEXT);
        if (stop) send(snapshot.emergency() ? Action.EMERGENCY_RELEASE : Action.EMERGENCY_APPLY);
    }

    public static void send(Action action) {
        send(action, 0);
    }

    public static void send(Action action, int notch) {
        if (!riding()) return;
        heartbeat = 0;
        sequence++;
        PacketDistributor.sendToServer(new TrainControlInputPayload(snapshot.session(), sequence, action, notch));
    }

    /**
     * Sends an explicit open or close target for the ridden car. Several drivers pressing the key before the
     * synced target arrives repeat the same request instead of toggling it back on the server.
     */
    private static void door(VehicleDoor side) {
        var vehicle = riddenVehicle();
        if (vehicle == null) return;
        boolean open = !vehicle.isDoorOpen(doorSide(side));
        send(side == VehicleDoor.LEFT
                ? (open ? Action.DOOR_LEFT_OPEN : Action.DOOR_LEFT_CLOSE)
                : (open ? Action.DOOR_RIGHT_OPEN : Action.DOOR_RIGHT_CLOSE));
    }

    public static VehicleDoor doorSide(VehicleDoor side) {
        return side.inFrame(snapshot.front().sign());
    }

    public static void render(RenderGuiEvent.Post event) {
        if (!consoleVisible()) return;
        TrainDrivingOverlay.render(event.getGuiGraphics(), snapshot,
                event.getPartialTick().getGameTimeDeltaPartialTick(false),
                usingCursor() ? mouseX() : -1, usingCursor() ? mouseY() : -1);
    }

    public static void guiLayer(RenderGuiLayerEvent.Pre event) {
        if (!consoleVisible()) return;
        var mc = Minecraft.getInstance();
        int clearance = TrainDrivingOverlay.bottomClearance();
        mc.gui.leftHeight = Math.max(mc.gui.leftHeight, clearance);
        mc.gui.rightHeight = Math.max(mc.gui.rightHeight, clearance);
        var name = event.getName();
        if (name.equals(VanillaGuiLayers.HOTBAR) || name.equals(VanillaGuiLayers.EXPERIENCE_BAR)
                || name.equals(VanillaGuiLayers.EXPERIENCE_LEVEL) || name.equals(VanillaGuiLayers.JUMP_METER)
                || name.equals(VanillaGuiLayers.SELECTED_ITEM_NAME)) event.setCanceled(true);
    }

    public static void chatOverlay(CustomizeGuiOverlayEvent.Chat event) {
        if (consoleVisible()) event.setPosY(Math.min(event.getPosY(),
                Minecraft.getInstance().getWindow().getGuiScaledHeight() - TrainDrivingOverlay.bottomClearance()));
    }

    public static Component direction(TrainControlSyncPayload state) {
        return Component.translatable(PREFIX + "direction." + state.reverser().name().toLowerCase(Locale.ROOT));
    }

    public static Component condition(TrainControlSyncPayload state) {
        boolean releasing = state.powerNotch() > 0 && state.brakeForce() > 0 && state.reverser() != Reverser.NEUTRAL;
        return Component.translatable(PREFIX + (state.emergency() ? "state.emergency"
                : state.brakeNotch() > 0 ? "state.brake_applied" : releasing ? "state.brake_releasing"
                : state.speed() != 0 ? "state.running"
                : state.stopReason() == StopReason.END_OF_ROUTE ? "state.end_of_route" : "state.stopped"));
    }

    public static void clear() {
        snapshot = null;
        sequence = 0;
        silence = heartbeat = 0;
        TrainDrivingOverlay.clear();
        TrainMonitorOverlay.clear();
        updateCursor();
    }

    public static void unload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) clear(); }
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public static void clonePlayer(ClientPlayerNetworkEvent.Clone event) { clear(); }
}
