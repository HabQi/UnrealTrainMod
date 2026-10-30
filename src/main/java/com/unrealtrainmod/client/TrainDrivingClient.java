/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrainControlInputPayload;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import com.unrealtrainmod.train.TrainControlProfile;
import com.unrealtrainmod.train.TrainControlState.Reverser;
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

/** Local handle intentions; speed and position are always read from the server. */
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
    private static TrainControlSyncPayload snapshot;
    private static long sequence;
    private static long intentSequence;
    private static int handle, silence, heartbeat;
    private static Reverser reverser;
    private static boolean emergency;
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
    }

    private static boolean riding() {
        var mc = Minecraft.getInstance();
        return snapshot != null && mc.player != null && mc.level != null
                && mc.level.dimension().location().equals(snapshot.dimension())
                && mc.player.getVehicle() instanceof TrainEntity entity && entity.getUUID().equals(snapshot.vehicle());
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
        if (newSession) { sequence = incoming.sequence(); intentSequence = sequence; }
        snapshot = incoming;
        silence = 0;
        if (newSession || incoming.sequence() >= intentSequence) {
            handle = incoming.handle();
            reverser = incoming.reverser();
            emergency = incoming.emergency();
        }
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
        for (var key : new KeyMapping[] {POWER, BRAKE, REVERSER, EMERGENCY}) while (key.consumeClick()) { }
        if (riding() && ++heartbeat >= 10) send(false);
    }

    public static void keyPressed(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !CONTEXT.isActive()) return;
        press(POWER.matches(event.getKey(), event.getScanCode()), BRAKE.matches(event.getKey(), event.getScanCode()),
                REVERSER.matches(event.getKey(), event.getScanCode()), EMERGENCY.matches(event.getKey(), event.getScanCode()));
    }

    public static void mousePressed(InputEvent.MouseButton.Post event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !CONTEXT.isActive()) return;
        press(POWER.matchesMouse(event.getButton()), BRAKE.matchesMouse(event.getButton()),
                REVERSER.matchesMouse(event.getButton()), EMERGENCY.matchesMouse(event.getButton()));
    }

    /** Consume actual press events, so quick taps are retained and keyboard repeat cannot change a notch. */
    private static void press(boolean power, boolean brake, boolean direction, boolean stop) {
        boolean changed = false;
        if (!emergency && power && !brake) {
            handle = Math.min(TrainControlProfile.POWER_NOTCHES, handle + 1);
            changed = true;
        }
        if (!emergency && brake && !power) {
            handle = Math.max(-TrainControlProfile.BRAKE_NOTCHES, handle - 1);
            changed = true;
        }
        if (direction) { reverser = reverser.next(); changed = true; }
        if (stop) { emergency = !emergency; handle = 0; changed = true; }
        if (changed) send(true);
    }

    private static void send(boolean changed) {
        heartbeat = 0;
        sequence++;
        if (changed) intentSequence = sequence;
        PacketDistributor.sendToServer(new TrainControlInputPayload(snapshot.session(), sequence, handle, reverser, emergency));
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
                : releasing ? "state.brake_releasing" : state.speed() == 0 ? "state.stopped" : "state.running"));
        Component brakeDemand = state.emergency() ? Component.translatable(PREFIX + "brake_emergency")
                : Component.literal(number(Math.max(0, -state.handle()) * 100.0 / TrainControlProfile.BRAKE_NOTCHES) + "%");
        Component[] lines = {
            Component.translatable(PREFIX + "speed", number(Math.abs(state.speed()) * 3.6)),
            Component.translatable(PREFIX + "control", direction, TrainControlProfile.notch(state.handle()), condition),
            Component.translatable(PREFIX + "motion", number(state.acceleration()), number(state.grade() * 1000)),
            Component.translatable(PREFIX + "traction", number(state.tractionForce() / 1000), number(state.tractionPower() / 1000)),
            Component.translatable(PREFIX + "brake", brakeDemand, number(state.brakeForce() / 1000)),
            Component.translatable(PREFIX + "help", POWER.getTranslatedKeyMessage(), BRAKE.getTranslatedKeyMessage(),
                    REVERSER.getTranslatedKeyMessage(), EMERGENCY.getTranslatedKeyMessage(), mc.options.keyShift.getTranslatedKeyMessage())
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
        intentSequence = 0;
        silence = heartbeat = handle = 0;
        reverser = null;
        emergency = false;
        hudSnapshot = null;
        hudLanguage = null;
        hudFont = null;
        hudRows = List.of();
    }

    public static void unload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) clear(); }
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public static void clonePlayer(ClientPlayerNetworkEvent.Clone event) { clear(); }
}
