/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrainControlInputPayload;
import com.unrealtrainmod.network.payload.TrainControlInputPayload.Action;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import com.unrealtrainmod.train.TrainControlProfile;
import com.unrealtrainmod.train.TrainControlState.Reverser;
import com.unrealtrainmod.train.TrainConsist.StopReason;
import com.unrealtrainmod.vehicle.VehicleDoor;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Discrete driving requests; shared controls and motion always come from the server. */
public final class TrainDrivingClient {
    private static final String PREFIX = "gui.unrealtrainmod.driving.";
    private static final IKeyConflictContext CONTEXT = new IKeyConflictContext() {
        @Override public boolean isActive() { return Minecraft.getInstance().screen == null && riding(); }
        @Override public boolean conflicts(IKeyConflictContext other) { return other == this || other == KeyConflictContext.IN_GAME; }
    };
    private static final KeyMapping POWER = key("power", GLFW.GLFW_KEY_W);
    private static final KeyMapping BRAKE = key("brake", GLFW.GLFW_KEY_S);
    private static final KeyMapping REVERSER = key("reverser", GLFW.GLFW_KEY_R);
    private static final KeyMapping EMERGENCY = key("emergency", GLFW.GLFW_KEY_SPACE);
    private static final KeyMapping DOOR_LEFT = key("door_left", GLFW.GLFW_KEY_J);
    private static final KeyMapping DOOR_RIGHT = key("door_right", GLFW.GLFW_KEY_K);
    private static TrainControlSyncPayload snapshot;
    private static long sequence;
    private static int silence, heartbeat;
    private static TrainControlSyncPayload hudSnapshot;
    private static Language hudLanguage;
    private static Font hudFont;
    private static int hudWidth;
    private static List<FormattedCharSequence> hudRows = List.of();

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
    }

    private static TrainEntity riddenVehicle() {
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

    public static void onSync(TrackSyncEvent event) {
        if (!(event.payload() instanceof TrainControlSyncPayload incoming)) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(incoming.dimension())) return;
        if (!incoming.open()) {
            if (snapshot != null && snapshot.session().equals(incoming.session())) clear();
            return;
        }
        boolean newSession = snapshot == null || !snapshot.session().equals(incoming.session());
        if (newSession) sequence = incoming.sequence();
        snapshot = incoming;
        silence = 0;
        if (incoming.feedback() != TrainControlSyncPayload.Feedback.NONE && mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(PREFIX
                    + incoming.feedback().name().toLowerCase(Locale.ROOT)), true);
        }
    }

    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (snapshot == null || mc.isPaused()) return;
        if (mc.level == null || mc.player == null || !mc.player.isAlive()
                || !mc.level.dimension().location().equals(snapshot.dimension()) || ++silence > 60) {
            clear();
            return;
        }
        for (var key : new KeyMapping[] {POWER, BRAKE, REVERSER, EMERGENCY, DOOR_LEFT, DOOR_RIGHT}) while (key.consumeClick()) { }
        if (riding() && ++heartbeat >= 10) send(Action.HEARTBEAT);
    }

    public static void keyPressed(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !CONTEXT.isActive()) return;
        press(POWER.matches(event.getKey(), event.getScanCode()), BRAKE.matches(event.getKey(), event.getScanCode()),
                REVERSER.matches(event.getKey(), event.getScanCode()), EMERGENCY.matches(event.getKey(), event.getScanCode()));
        if (DOOR_LEFT.matches(event.getKey(), event.getScanCode())) door(VehicleDoor.LEFT);
        if (DOOR_RIGHT.matches(event.getKey(), event.getScanCode())) door(VehicleDoor.RIGHT);
    }

    public static void mousePressed(InputEvent.MouseButton.Post event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !CONTEXT.isActive()) return;
        press(POWER.matchesMouse(event.getButton()), BRAKE.matchesMouse(event.getButton()),
                REVERSER.matchesMouse(event.getButton()), EMERGENCY.matchesMouse(event.getButton()));
        if (DOOR_LEFT.matchesMouse(event.getButton())) door(VehicleDoor.LEFT);
        if (DOOR_RIGHT.matchesMouse(event.getButton())) door(VehicleDoor.RIGHT);
    }

    /** Consume actual press events, so quick taps are retained and keyboard repeat cannot change a notch. */
    private static void press(boolean power, boolean brake, boolean direction, boolean stop) {
        if (power && !brake) send(Action.HANDLE_UP);
        if (brake && !power) send(Action.HANDLE_DOWN);
        if (direction) send(Action.REVERSER_NEXT);
        if (stop) send(snapshot.emergency() ? Action.EMERGENCY_RELEASE : Action.EMERGENCY_APPLY);
    }

    private static void send(Action action) {
        heartbeat = 0;
        sequence++;
        PacketDistributor.sendToServer(new TrainControlInputPayload(snapshot.session(), sequence, action));
    }

    /**
     * Sends an explicit open or close target for the ridden car. Several drivers pressing the key before the
     * synced target arrives repeat the same request instead of toggling it back on the server.
     */
    private static void door(VehicleDoor side) {
        var vehicle = riddenVehicle();
        if (vehicle == null) return;
        boolean open = !vehicle.isDoorOpen(side);
        send(side == VehicleDoor.LEFT
                ? (open ? Action.DOOR_LEFT_OPEN : Action.DOOR_LEFT_CLOSE)
                : (open ? Action.DOOR_RIGHT_OPEN : Action.DOOR_RIGHT_CLOSE));
    }

    public static void movement(MovementInputUpdateEvent event) {
        if (!riding()) return;
        var input = event.getInput();
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = input.jumping = false;
        // Keep shiftKeyDown: the normal dismount packet ends the server session.
    }

    public static void render(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!riding() || mc.options.hideGui) return;
        var graphics = event.getGuiGraphics();
        int width = Math.min(graphics.guiWidth() - 24, 430);
        // The server snapshot changes around 10 Hz; rendering may run hundreds of times per second.
        if (hudSnapshot != snapshot || hudWidth != width || hudLanguage != Language.getInstance() || hudFont != mc.font) {
            rebuildHud(mc, width);
        }
        int height = hudRows.size() * 10 + 12;
        int x = 12, y = Math.max(12, graphics.guiHeight() - height - 48);
        graphics.fill(x - 4, y - 4, x + width + 4, y + height - 4, 0xB0000000);
        for (int i = 0; i < hudRows.size(); i++) graphics.drawString(mc.font, hudRows.get(i), x, y + i * 10, 0xFFFFFF, false);
    }

    private static void rebuildHud(Minecraft mc, int width) {
        var state = snapshot;
        var direction = Component.translatable(PREFIX + "direction." + state.reverser().name().toLowerCase(Locale.ROOT));
        boolean releasing = state.handle() > 0 && state.brakeForce() > 0 && state.reverser() != Reverser.NEUTRAL;
        var condition = Component.translatable(PREFIX + (state.emergency() ? "state.emergency"
                : releasing ? "state.brake_releasing" : state.speed() != 0 ? "state.running"
                : state.stopReason() == StopReason.END_OF_ROUTE ? "state.end_of_route" : "state.stopped"));
        Component brakeDemand = state.emergency() ? Component.translatable(PREFIX + "brake_emergency")
                : Component.literal(number(Math.max(0, -state.handle()) * 100.0 / TrainControlProfile.BRAKE_NOTCHES) + "%");
        Component[] lines = {
            Component.translatable(PREFIX + "speed", number(Math.abs(state.speed()) * 3.6)),
            Component.translatable(PREFIX + "control", direction, TrainControlProfile.notch(state.handle()), condition),
            Component.translatable(PREFIX + "motion", number(state.acceleration()), number(state.grade() * 1000)),
            Component.translatable(PREFIX + "traction", number(state.tractionForce() / 1000), number(state.tractionPower() / 1000)),
            Component.translatable(PREFIX + "brake", brakeDemand, number(state.brakeForce() / 1000)),
            Component.translatable(PREFIX + "help", POWER.getTranslatedKeyMessage(), BRAKE.getTranslatedKeyMessage(),
                    REVERSER.getTranslatedKeyMessage(), EMERGENCY.getTranslatedKeyMessage(),
                    DOOR_LEFT.getTranslatedKeyMessage(), DOOR_RIGHT.getTranslatedKeyMessage(),
                    mc.options.keyShift.getTranslatedKeyMessage())
        };
        var wrapped = new ArrayList<FormattedCharSequence>();
        for (var line : lines) wrapped.addAll(mc.font.split(line, width));
        hudRows = List.copyOf(wrapped);
        hudSnapshot = state;
        hudWidth = width;
        hudLanguage = Language.getInstance();
        hudFont = mc.font;
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    public static void clear() {
        snapshot = null;
        sequence = 0;
        silence = heartbeat = 0;
        hudSnapshot = null;
        hudLanguage = null;
        hudFont = null;
        hudRows = List.of();
    }

    public static void unload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) clear(); }
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public static void clonePlayer(ClientPlayerNetworkEvent.Clone event) { clear(); }
}
