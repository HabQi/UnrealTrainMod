/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import com.mojang.math.Axis;
import com.unrealtrainmod.client.TrainDrivingClient;
import com.unrealtrainmod.network.payload.TrainControlInputPayload.Action;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import com.unrealtrainmod.train.TrainControlProfile;
import com.unrealtrainmod.vehicle.VehicleDoor;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Always-visible driver desk; instruments and both handles show authoritative state. */
public final class TrainDrivingOverlay {
    private static final int BACKGROUND = 0xF2141D27, PANEL = 0xFF0C131C, EDGE = 0xFF344656;
    private static final int TEXT = 0xFFE6F1F6, MUTED = 0xFF8B9DAC, CYAN = 0xFF62DFE0;
    private static final int GREEN = 0xFF75DC9D, AMBER = 0xFFFFC66B, RED = 0xFFFF706B;
    private static final int GAP = 4, HEIGHT = 70, DOOR_WIDTH = 22;
    private static final int MONITOR_HEIGHT = 170;
    private static final int HANDLE_TOP = 16, HANDLE_BOTTOM = 55;
    /** Preserve the compact layout while snapping glyphs to whole framebuffer pixels. */
    private static final float TEXT_SCALE = 0.8F;
    private static TrainControlSyncPayload displayed;
    private static Language language;
    private static String speed;
    private static Component motion, controls;
    private static Component[] values;
    private static Boolean draggingPower;
    private static double handleGrabOffset;
    private static int requestedNotch = -1;
    private static boolean monitorExpanded;

    private TrainDrivingOverlay() {}

    private record Box(int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private record Layout(Box desk, Box speed, Box monitor, Box data, Box handle, Box switches,
                          int monitorCanvasWidth, float monitorScale) {
        Box power() { return new Box(handle.x + handle.width / 2 + 2, handle.y, handle.width - handle.width / 2 - 2, handle.height); }
        Box brake() { return new Box(handle.x, handle.y, handle.width / 2 - 2, handle.height); }
        int switchButtonWidth() { return (switches.width - 12) / 2; }
        Box reverser() { return new Box(switches.x + 4, switches.y + 4, switchButtonWidth(), 24); }
        Box emergency() { return new Box(switches.x + 4, switches.y + 32, switchButtonWidth(), 25); }
        Box headLight() { return new Box(reverser().x + switchButtonWidth() + 4, reverser().y,
                switches.width - 12 - switchButtonWidth(), 24); }
        Box tailLight() { return new Box(headLight().x, emergency().y, headLight().width, 25); }
        Box leftDoor() { return new Box(desk.x + GAP, speed.y, DOOR_WIDTH, speed.height); }
        Box rightDoor() { return new Box(desk.x + desk.width - GAP - DOOR_WIDTH, speed.y, DOOR_WIDTH, speed.height); }
    }

    private static Layout layout() {
        var window = Minecraft.getInstance().getWindow();
        int availableWidth = Math.min(1000, window.getGuiScaledWidth() - 12);
        boolean wide = availableWidth >= 700;
        int speedWidth = availableWidth < 360 ? 56 : 64;
        int handleWidth = availableWidth < 360 ? 68 : 84;
        int switchWidth = Math.min(104, Math.max(56, availableWidth / 6));
        int dataWidth = wide ? Math.min(160, availableWidth / 6) : 0;
        int fixedWidth = 2 * DOOR_WIDTH + (wide ? 8 : 7) * GAP
                + speedWidth + dataWidth + handleWidth + switchWidth;
        int monitorCanvasWidth = Math.max(120, availableWidth - fixedWidth);
        float monitorScale = Math.min(1F, (availableWidth - fixedWidth) / (float) monitorCanvasWidth);
        monitorScale = Math.min(monitorScale, (window.getGuiScaledHeight() - 16F) / MONITOR_HEIGHT);
        int contentHeight = HEIGHT - 2 * GAP;
        if (!monitorExpanded) monitorScale = Math.min(monitorScale, contentHeight / (float) MONITOR_HEIGHT);
        int monitorWidth = Math.round(monitorCanvasWidth * monitorScale);
        int monitorHeight = Math.round(MONITOR_HEIGHT * monitorScale);
        int width = fixedWidth + monitorWidth;
        int left = (window.getGuiScaledWidth() - width) / 2, top = window.getGuiScaledHeight() - HEIGHT - 6;
        int contentY = top + GAP;
        var speedBox = new Box(left + 2 * GAP + DOOR_WIDTH, contentY, speedWidth, contentHeight);
        int monitorY = contentY + contentHeight - monitorHeight;
        var monitor = new Box(speedBox.x + speedWidth + GAP, monitorY, monitorWidth, monitorHeight);
        var data = new Box(monitor.x + monitorWidth + GAP, contentY, dataWidth, contentHeight);
        int handleX = wide ? data.x + dataWidth + GAP : data.x;
        var handle = new Box(handleX, contentY, handleWidth, contentHeight);
        var switches = new Box(handle.x + handle.width + GAP, contentY, switchWidth, contentHeight);
        return new Layout(new Box(left, top, width, HEIGHT), speedBox, monitor, data, handle, switches,
                monitorCanvasWidth, monitorScale);
    }

    public static int bottomClearance() { return HEIGHT + 18; }

    private static Component text(String key, Object... args) {
        return Component.translatable("gui.unrealtrainmod.driving.console." + key, args);
    }

    private static Component drivingText(String key, Object... args) {
        return Component.translatable("gui.unrealtrainmod.driving." + key, args);
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    private static void refresh(TrainControlSyncPayload state) {
        if (displayed == state && language == Language.getInstance()) return;
        displayed = state;
        language = Language.getInstance();
        speed = String.format(Locale.ROOT, "%.1f", Math.abs(state.speed()) * 3.6);
        motion = text(state.speed() > 0 ? "toward_front" : state.speed() < 0 ? "toward_back" : "at_rest");
        controls = TrainDrivingClient.controlHint();
        values = new Component[] {
            Component.literal(number(state.acceleration()) + " m/s²"),
            Component.literal(number(state.grade() * 1000) + " ‰"),
            Component.literal(number(state.tractionForce() / 1000) + " kN"),
            Component.literal(number(state.brakeForce() / 1000) + " kN")
        };
    }

    public static void render(GuiGraphics graphics, TrainControlSyncPayload state, float partialTick,
                              double mouseX, double mouseY) {
        refresh(state);
        var font = Minecraft.getInstance().font;
        var layout = layout();
        var desk = layout.desk;
        graphics.fill(desk.x - 2, desk.y - 2, desk.x + desk.width + 2, desk.y + desk.height + 2, 0x66000000);
        panel(graphics, desk, BACKGROUND);
        graphics.fill(desk.x + 1, desk.y + 1, desk.x + desk.width - 1, desk.y + 3, EDGE);
        speedometer(graphics, font, layout.speed, state);
        var monitor = layout.monitor;
        TrainMonitorOverlay.render(graphics, state, monitor.x, monitor.y,
                layout.monitorCanvasWidth, MONITOR_HEIGHT, layout.monitorScale, !monitorExpanded, monitor.contains(mouseX, mouseY));
        if (layout.data.width > 0) telemetry(graphics, font, layout.data);
        handle(graphics, font, layout.brake(), false, state, mouseX, mouseY);
        handle(graphics, font, layout.power(), true, state, mouseX, mouseY);
        switches(graphics, font, layout, state, mouseX, mouseY);
        door(graphics, font, layout.leftDoor(), VehicleDoor.LEFT, partialTick, mouseX, mouseY);
        door(graphics, font, layout.rightDoor(), VehicleDoor.RIGHT, partialTick, mouseX, mouseY);
        tooltip(graphics, font, layout, state, partialTick, mouseX, mouseY);
    }

    private static void speedometer(GuiGraphics graphics, Font font, Box box, TrainControlSyncPayload state) {
        panel(graphics, box, PANEL);
        int radius = Math.min((box.width - 20) / 2, (box.height - 29) * 2 / 3);
        int cx = box.x + box.width / 2, cy = box.y + radius + 4;
        double velocity = Math.abs(state.speed()) * 3.6;
        int maximum = Math.max(120, (int) Math.ceil(velocity / 60) * 60);
        for (int tick = 0; tick <= 12; tick++) {
            float angle = 150 + tick * 20;
            radialLine(graphics, cx, cy, radius - (tick % 2 == 0 ? 6 : 3), radius, angle,
                    tick / 12.0 <= velocity / maximum ? CYAN : EDGE);
        }
        radialLine(graphics, cx, cy, 0, radius - 9, (float) (150 + 240 * velocity / maximum), CYAN);
        graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, TEXT);
        int scaleY = cy + radius / 2 + 3;
        label(graphics, font, Component.literal("0"), cx - radius - 3, scaleY, 8, MUTED);
        String limit = Integer.toString(maximum);
        float unitScale = textScale();
        label(graphics, font, Component.literal(limit), cx + radius + 3 - (int) (font.width(limit) * unitScale),
                scaleY, 24, MUTED);
        int speedWidth = font.width(speed);
        int unitWidth = (int) Math.ceil(font.width("km/h") * unitScale);
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        float desiredScale = Math.min(1.5F, (box.width - unitWidth - 16) / (float) speedWidth);
        float scale = Math.max(1, (int) Math.floor(desiredScale * guiScale)) / guiScale;
        float readingWidth = speedWidth * scale + 4 + unitWidth;
        float readingX = cx - readingWidth / 2;
        int readingY = box.y + box.height - 15;
        drawText(graphics, font, speed, readingX, readingY, scale, TEXT);
        label(graphics, font, Component.literal("km/h"), (int) (readingX + speedWidth * scale + 4),
                readingY + (int) ((scale - unitScale) * font.lineHeight), unitWidth, MUTED);
    }

    private static void radialLine(GuiGraphics graphics, int x, int y, int from, int to, float angle, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(angle));
        graphics.fill(from, 0, to, 1, color);
        graphics.pose().popPose();
    }

    private static void telemetry(GuiGraphics graphics, Font font, Box box) {
        panel(graphics, box, PANEL);
        String[] keys = {"acceleration", "grade", "traction", "brake_force"};
        float scale = textScale();
        for (int i = 0; i < keys.length; i++) {
            int y = box.y + 7 + i * 14;
            int valueWidth = (int) Math.ceil(font.width(values[i]) * scale);
            label(graphics, font, text(keys[i]), box.x + 5, y, box.width - valueWidth - 14, MUTED);
            label(graphics, font, values[i], box.x + box.width - valueWidth - 5, y, valueWidth, TEXT);
        }
    }

    private static void handle(GuiGraphics graphics, Font font, Box box, boolean power, TrainControlSyncPayload state,
                               double mouseX, double mouseY) {
        panel(graphics, box, !state.emergency() && box.contains(mouseX, mouseY) ? 0xFF172733 : PANEL);
        int value = power ? state.powerNotch() : state.brakeNotch();
        int count = power ? TrainControlProfile.POWER_NOTCHES : TrainControlProfile.BRAKE_NOTCHES;
        int color = state.emergency() ? RED : power ? CYAN : AMBER;
        int cx = box.x + box.width / 2, top = box.y + HANDLE_TOP, bottom = box.y + HANDLE_BOTTOM;
        graphics.fill(cx - 2, top, cx + 3, bottom + 1, EDGE);
        for (int i = 0; i <= count; i++) {
            int y = handlePosition(box, i, count);
            graphics.fill(cx - 7, y, cx + 8, y + 1, i <= value ? color : EDGE);
        }
        int position = handlePosition(box, value, count);
        graphics.fill(cx - 11, position - 3, cx + 12, position + 4, 0xFF05090E);
        graphics.fill(cx - 10, position - 2, cx + 11, position + 3, state.emergency() ? RED : TEXT);
        graphics.fill(cx - 5, position - 1, cx + 6, position + 1, color);
        String notch = state.emergency() && !power ? "EB" : value == 0 ? "0" : (power ? "P" : "B") + value;
        int notchWidth = (int) Math.ceil(font.width(notch) * textScale());
        label(graphics, font, text(power ? "power_handle" : "brake_handle"), box.x + 4, box.y + 5,
                box.width - notchWidth - 11, MUTED);
        label(graphics, font, Component.literal(notch), box.x + box.width - notchWidth - 4, box.y + 5, notchWidth, color);
    }

    private static int handlePosition(Box box, int notch, int count) {
        return box.y + HANDLE_BOTTOM - Math.round(notch * (HANDLE_BOTTOM - HANDLE_TOP) / (float) count);
    }

    private static void switches(GuiGraphics graphics, Font font, Layout layout, TrainControlSyncPayload state,
                                 double mouseX, double mouseY) {
        var box = layout.switches;
        panel(graphics, box, PANEL);
        button(graphics, font, layout.reverser(), text("reverser", TrainDrivingClient.direction(state)),
                state.powerNotch() == 0 && state.brakeNotch() == 0, CYAN, mouseX, mouseY);
        button(graphics, font, layout.emergency(), text(state.emergency() ? "emergency_release" : "emergency"),
                !state.emergency() || state.speed() == 0, RED, mouseX, mouseY);
        lightButton(graphics, font, layout.headLight(), true, TrainDrivingClient.headLightOn(), mouseX, mouseY);
        lightButton(graphics, font, layout.tailLight(), false, TrainDrivingClient.tailLightOn(), mouseX, mouseY);
        if (state.emergency()) graphics.renderOutline(layout.emergency().x, layout.emergency().y,
                layout.emergency().width, layout.emergency().height, RED);
    }

    private static void lightButton(GuiGraphics graphics, Font font, Box box, boolean head, boolean on,
                                    double mouseX, double mouseY) {
        button(graphics, font, box, text(head ? "head_light" : "tail_light"), true,
                on ? (head ? AMBER : RED) : MUTED, mouseX, mouseY);
        if (on) graphics.renderOutline(box.x, box.y, box.width, box.height, head ? AMBER : RED);
    }

    private static void door(GuiGraphics graphics, Font font, Box box, VehicleDoor side, float partialTick,
                             double mouseX, double mouseY) {
        var vehicle = TrainDrivingClient.riddenVehicle();
        var vehicleSide = TrainDrivingClient.doorSide(side);
        boolean open = vehicle.isDoorOpen(vehicleSide);
        panel(graphics, box, box.contains(mouseX, mouseY) ? 0xFF293E4A : PANEL);
        int color = open ? AMBER : GREEN;
        var characters = text(side == VehicleDoor.LEFT ? "left_door" : "right_door").getString().codePoints().toArray();
        float scale = textScale();
        int lineHeight = Math.max(8, (int) Math.ceil(font.lineHeight * scale));
        int y = box.y + (box.height - characters.length * lineHeight) / 2;
        for (int character : characters) {
            var glyph = Component.literal(Character.toString(character));
            int glyphWidth = (int) Math.ceil(font.width(glyph) * scale);
            label(graphics, font, glyph, box.x + (box.width - glyphWidth) / 2, y, glyphWidth, color);
            y += lineHeight;
        }
        int barX = side == VehicleDoor.LEFT ? box.x + 2 : box.x + box.width - 4;
        graphics.fill(barX, box.y + 2, barX + 2, box.y + box.height - 2, EDGE);
        int progress = Math.round((box.height - 4) * vehicle.doorMovement(vehicleSide, partialTick));
        graphics.fill(barX, box.y + box.height - 2 - progress, barX + 2, box.y + box.height - 2, AMBER);
    }

    private static void button(GuiGraphics graphics, Font font, Box box, Component title, boolean enabled,
                               int color, double mouseX, double mouseY) {
        panel(graphics, box, enabled && box.contains(mouseX, mouseY) ? 0xFF293E4A : 0xFF1A2834);
        graphics.fill(box.x + 3, box.y + 4, box.x + 5, box.y + box.height - 4, enabled ? color : EDGE);
        float scale = Math.min(textScale(), (box.width - 13F) / Math.max(1, font.width(title)));
        drawText(graphics, font, title.getString(), box.x + 9,
                box.y + (box.height - font.lineHeight * scale) / 2, scale, enabled ? TEXT : MUTED);
    }

    private static void panel(GuiGraphics graphics, Box box, int color) {
        graphics.fill(box.x, box.y, box.x + box.width, box.y + box.height, color);
        graphics.renderOutline(box.x, box.y, box.width, box.height, EDGE);
    }

    private static void label(GuiGraphics graphics, Font font, Component value, int x, int y, int width, int color) {
        float scale = textScale();
        String visible = font.plainSubstrByWidth(value.getString(), Math.max(0, (int) (width / scale)));
        drawText(graphics, font, visible, x, y, scale, color);
    }

    private static void drawText(GuiGraphics graphics, Font font, String value,
                                 float x, float y, float scale, int color) {
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(Math.round(x * guiScale) / guiScale, Math.round(y * guiScale) / guiScale, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.drawString(font, value, 0, 0, color, false);
        } finally {
            graphics.pose().popPose();
        }
    }

    /** Returns the compact text scale as an integer number of framebuffer pixels. */
    private static float textScale() {
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        return Math.max(1, Math.round(TEXT_SCALE * guiScale)) / guiScale;
    }

    private static void tooltip(GuiGraphics graphics, Font font, Layout layout, TrainControlSyncPayload state,
                                float partialTick, double mouseX, double mouseY) {
        if (mouseX < 0 || mouseY < 0) return;
        Component tip = null;
        if (layout.monitor.contains(mouseX, mouseY)) {
            tip = text(monitorExpanded ? "monitor_collapse" : "monitor_expand");
        } else if (layout.speed.contains(mouseX, mouseY) || layout.data.contains(mouseX, mouseY)) {
            tip = Component.empty().append(TrainDrivingClient.condition(state)).append("\n").append(motion).append("\n")
                    .append(drivingText("motion", number(state.acceleration()), number(state.grade() * 1000))).append("\n")
                    .append(text("grade_hint")).append("\n")
                    .append(drivingText("traction", number(state.tractionForce() / 1000))).append("\n")
                    .append(drivingText("brake", number(state.brakeForce() / 1000)));
        } else if (layout.handle.contains(mouseX, mouseY)) {
            tip = Component.empty().append(text("handle_hint")).append("\n").append(controls);
        } else if (layout.reverser().contains(mouseX, mouseY)) {
            tip = Component.empty().append(text("reverser", TrainDrivingClient.direction(state)))
                    .append("\n").append(drivingText("reverser_locked"));
        } else if (layout.emergency().contains(mouseX, mouseY)) {
            tip = text("emergency_hint");
        } else if (layout.headLight().contains(mouseX, mouseY)) {
            tip = text("light_hint", text("head_light"), text(TrainDrivingClient.riddenVehicle().isHeadLightOn() ? "light_on" : "light_off"));
        } else if (layout.tailLight().contains(mouseX, mouseY)) {
            tip = text("light_hint", text("tail_light"), text(TrainDrivingClient.riddenVehicle().isTailLightOn() ? "light_on" : "light_off"));
        } else if (layout.leftDoor().contains(mouseX, mouseY)) {
            tip = doorHint(VehicleDoor.LEFT, partialTick);
        } else if (layout.rightDoor().contains(mouseX, mouseY)) {
            tip = doorHint(VehicleDoor.RIGHT, partialTick);
        }
        if (tip != null) graphics.renderTooltip(font, font.split(tip,
                Math.min(320, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 16)), (int) mouseX, (int) mouseY);
    }

    private static Component doorHint(VehicleDoor side, float partialTick) {
        var vehicle = TrainDrivingClient.riddenVehicle();
        var vehicleSide = TrainDrivingClient.doorSide(side);
        return text("door_hint", text(vehicle.isDoorOpen(vehicleSide) ? "close" : "open"),
                Math.round(vehicle.doorMovement(vehicleSide, partialTick) * 100));
    }

    public static void click(double mouseX, double mouseY) {
        var state = TrainDrivingClient.state();
        var layout = layout();
        if (layout.monitor.contains(mouseX, mouseY)) {
            release();
            monitorExpanded = !monitorExpanded;
            return;
        }
        if (!state.emergency() && mouseY >= layout.handle.y + HANDLE_TOP - 3
                && (layout.power().contains(mouseX, mouseY) || layout.brake().contains(mouseX, mouseY))) {
            draggingPower = layout.power().contains(mouseX, mouseY);
            var box = draggingPower ? layout.power() : layout.brake();
            int count = draggingPower ? TrainControlProfile.POWER_NOTCHES : TrainControlProfile.BRAKE_NOTCHES;
            requestedNotch = draggingPower ? state.powerNotch() : state.brakeNotch();
            int position = handlePosition(box, requestedNotch, count);
            var grip = new Box(box.x + box.width / 2 - 11, position - 3, 23, 7);
            handleGrabOffset = grip.contains(mouseX, mouseY) ? mouseY - position : 0;
            drag(mouseY);
            return;
        }
        Action action = null;
        if (layout.reverser().contains(mouseX, mouseY)
                && state.powerNotch() == 0 && state.brakeNotch() == 0) action = Action.REVERSER_NEXT;
        else if (layout.emergency().contains(mouseX, mouseY) && (!state.emergency() || state.speed() == 0)) {
            action = state.emergency() ? Action.EMERGENCY_RELEASE : Action.EMERGENCY_APPLY;
        } else if (layout.headLight().contains(mouseX, mouseY)) {
            TrainDrivingClient.toggleLight(true);
            return;
        } else if (layout.tailLight().contains(mouseX, mouseY)) {
            TrainDrivingClient.toggleLight(false);
            return;
        } else if (layout.leftDoor().contains(mouseX, mouseY)) {
            action = TrainDrivingClient.riddenVehicle().isDoorOpen(TrainDrivingClient.doorSide(VehicleDoor.LEFT))
                    ? Action.DOOR_LEFT_CLOSE : Action.DOOR_LEFT_OPEN;
        } else if (layout.rightDoor().contains(mouseX, mouseY)) {
            action = TrainDrivingClient.riddenVehicle().isDoorOpen(TrainDrivingClient.doorSide(VehicleDoor.RIGHT))
                    ? Action.DOOR_RIGHT_CLOSE : Action.DOOR_RIGHT_OPEN;
        }
        if (action != null) TrainDrivingClient.send(action);
    }

    public static void scroll(double mouseX, double mouseY, double delta) {
        var state = TrainDrivingClient.state();
        var layout = layout();
        if (state.emergency() || draggingPower != null || delta == 0) return;
        boolean power = layout.power().contains(mouseX, mouseY);
        if (!power && !layout.brake().contains(mouseX, mouseY)) return;
        TrainDrivingClient.send(power ? (delta > 0 ? Action.POWER_UP : Action.POWER_DOWN)
                : (delta > 0 ? Action.BRAKE_UP : Action.BRAKE_DOWN));
    }

    public static void drag(double mouseY) {
        if (draggingPower == null) return;
        if (TrainDrivingClient.state().emergency()) { release(); return; }
        boolean power = draggingPower;
        var box = power ? layout().power() : layout().brake();
        int count = power ? TrainControlProfile.POWER_NOTCHES : TrainControlProfile.BRAKE_NOTCHES;
        int target = (int) Math.round(Math.clamp((box.y + HANDLE_BOTTOM - mouseY + handleGrabOffset)
                / (HANDLE_BOTTOM - HANDLE_TOP), 0, 1) * count);
        if (target != requestedNotch) {
            requestedNotch = target;
            TrainDrivingClient.send(power ? Action.POWER_SET : Action.BRAKE_SET, target);
        }
    }

    public static void release() {
        draggingPower = null;
        handleGrabOffset = 0;
        requestedNotch = -1;
    }

    public static void clear() {
        displayed = null;
        language = null;
        values = null;
        monitorExpanded = false;
        release();
    }
}
