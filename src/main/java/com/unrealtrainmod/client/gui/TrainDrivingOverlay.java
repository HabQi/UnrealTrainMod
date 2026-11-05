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
    private static final int GAP = 4, HEIGHT = 88, DOOR_WIDTH = 22;
    private static final int HANDLE_TOP = 19, HANDLE_BOTTOM = 60;
    private static final float TEXT_SCALE = 0.8F;
    private static TrainControlSyncPayload displayed;
    private static Language language;
    private static String speed;
    private static Component condition, motion, controls;
    private static Component[] values;
    private static Boolean draggingPower;
    private static int requestedNotch = -1;

    private TrainDrivingOverlay() {}

    private record Box(int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private record Layout(Box desk, Box speed, Box data, Box handle, Box switches) {
        Box power() { return new Box(handle.x + handle.width / 2 + 2, handle.y, handle.width - handle.width / 2 - 2, handle.height); }
        Box brake() { return new Box(handle.x, handle.y, handle.width / 2 - 2, handle.height); }
        Box reverser() { return new Box(switches.x + 4, switches.y + 5, switches.width - 8, 25); }
        Box emergency() { return new Box(switches.x + 4, switches.y + 35, switches.width - 8, 27); }
        Box leftDoor() { return new Box(desk.x + GAP, speed.y, DOOR_WIDTH, speed.height); }
        Box rightDoor() { return new Box(desk.x + desk.width - GAP - DOOR_WIDTH, speed.y, DOOR_WIDTH, speed.height); }
    }

    private static Layout layout() {
        var window = Minecraft.getInstance().getWindow();
        int width = Math.min(700, window.getGuiScaledWidth() - 12);
        int left = (window.getGuiScaledWidth() - width) / 2, top = window.getGuiScaledHeight() - HEIGHT - 6;
        boolean wide = width >= 560;
        int innerWidth = width - 2 * DOOR_WIDTH - 4 * GAP;
        int speedWidth = wide ? 88 : Math.max(68, width / 5);
        int handleWidth = wide ? 112 : (innerWidth - speedWidth - 2 * GAP) / 2;
        int switchWidth = wide ? 104 : innerWidth - speedWidth - handleWidth - 2 * GAP;
        int dataWidth = wide ? innerWidth - speedWidth - handleWidth - switchWidth - 3 * GAP : 0;
        int contentY = top + 17, contentHeight = HEIGHT - 21;
        var speedBox = new Box(left + 2 * GAP + DOOR_WIDTH, contentY, speedWidth, contentHeight);
        var data = new Box(speedBox.x + speedWidth + GAP, contentY, dataWidth, contentHeight);
        int handleX = wide ? data.x + dataWidth + GAP : data.x;
        var handle = new Box(handleX, contentY, handleWidth, contentHeight);
        var switches = new Box(handle.x + handle.width + GAP, contentY, switchWidth, contentHeight);
        return new Layout(new Box(left, top, width, HEIGHT), speedBox, data, handle, switches);
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
        condition = TrainDrivingClient.condition(state);
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
        int statusColor = state.emergency() ? RED : state.brakeNotch() > 0 ? AMBER : GREEN;
        graphics.fill(desk.x + 5, desk.y + 6, desk.x + 8, desk.y + 9, statusColor);
        label(graphics, font, condition, desk.x + 12, desk.y + 5, desk.width / 2 - 16, statusColor);
        speedometer(graphics, font, layout.speed, state);
        if (layout.data.width > 0) telemetry(graphics, font, layout.data);
        handle(graphics, font, layout.brake(), false, state, mouseX, mouseY);
        handle(graphics, font, layout.power(), true, state, mouseX, mouseY);
        switches(graphics, font, layout, state, mouseX, mouseY);
        door(graphics, font, layout.leftDoor(), VehicleDoor.LEFT, partialTick, mouseX, mouseY);
        door(graphics, font, layout.rightDoor(), VehicleDoor.RIGHT, partialTick, mouseX, mouseY);
        Component hint = TrainDrivingClient.usingCursor() ? text("cursor_active") : TrainDrivingClient.cursorHint();
        label(graphics, font, hint, desk.x + desk.width / 2, desk.y + 5, desk.width / 2 - 5,
                TrainDrivingClient.usingCursor() ? CYAN : MUTED);
        tooltip(graphics, font, layout, state, partialTick, mouseX, mouseY);
    }

    private static void speedometer(GuiGraphics graphics, Font font, Box box, TrainControlSyncPayload state) {
        panel(graphics, box, PANEL);
        int cx = box.x + box.width / 2, cy = box.y + 20, radius = 16;
        double velocity = Math.abs(state.speed()) * 3.6;
        int maximum = Math.max(120, (int) Math.ceil(velocity / 60) * 60);
        for (int tick = 0; tick <= 12; tick++) {
            float angle = 150 + tick * 20;
            radialLine(graphics, cx, cy, radius - (tick % 2 == 0 ? 4 : 2), radius, angle,
                    tick / 12.0 <= velocity / maximum ? CYAN : EDGE);
        }
        radialLine(graphics, cx, cy, 0, radius - 7, (float) (150 + 240 * velocity / maximum), CYAN);
        graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, TEXT);
        label(graphics, font, Component.literal("0"), cx - radius - 5, box.y + 30, 8, MUTED);
        String limit = Integer.toString(maximum);
        label(graphics, font, Component.literal(limit), cx + radius + 5 - (int) (font.width(limit) * TEXT_SCALE),
                box.y + 30, 24, MUTED);
        int speedWidth = font.width(speed);
        float scale = Math.min(1.25F, (box.width - 8) / (float) speedWidth);
        graphics.pose().pushPose();
        graphics.pose().translate(cx - speedWidth * scale / 2, box.y + 40, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, speed, 0, 0, TEXT, false);
        graphics.pose().popPose();
        int unitWidth = (int) Math.ceil(font.width("km/h") * TEXT_SCALE);
        label(graphics, font, Component.literal("km/h"), box.x + 4, box.y + 56, unitWidth, MUTED);
        label(graphics, font, motion, box.x + unitWidth + 9, box.y + 56, box.width - unitWidth - 13, MUTED);
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
        for (int i = 0; i < keys.length; i++) {
            int y = box.y + 7 + i * 14;
            int valueWidth = (int) Math.ceil(font.width(values[i]) * TEXT_SCALE);
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
            int y = bottom - i * (bottom - top) / count;
            graphics.fill(cx - 7, y, cx + 8, y + 1, i <= value ? color : EDGE);
        }
        int position = bottom - value * (bottom - top) / count;
        graphics.fill(cx - 11, position - 3, cx + 12, position + 4, 0xFF05090E);
        graphics.fill(cx - 10, position - 2, cx + 11, position + 3, state.emergency() ? RED : TEXT);
        graphics.fill(cx - 5, position - 1, cx + 6, position + 1, color);
        String notch = state.emergency() && !power ? "EB" : value == 0 ? "0" : (power ? "P" : "B") + value;
        int notchWidth = (int) Math.ceil(font.width(notch) * TEXT_SCALE);
        label(graphics, font, text(power ? "power_handle" : "brake_handle"), box.x + 4, box.y + 5,
                box.width - notchWidth - 11, MUTED);
        label(graphics, font, Component.literal(notch), box.x + box.width - notchWidth - 4, box.y + 5, notchWidth, color);
    }

    private static void switches(GuiGraphics graphics, Font font, Layout layout, TrainControlSyncPayload state,
                                 double mouseX, double mouseY) {
        var box = layout.switches;
        panel(graphics, box, PANEL);
        button(graphics, font, layout.reverser(), text("reverser", TrainDrivingClient.direction(state)),
                state.speed() == 0 && state.powerNotch() == 0 && state.brakeNotch() == 0, CYAN, mouseX, mouseY);
        button(graphics, font, layout.emergency(), text(state.emergency() ? "emergency_release" : "emergency"),
                !state.emergency() || state.speed() == 0, RED, mouseX, mouseY);
        if (state.emergency()) graphics.renderOutline(layout.emergency().x, layout.emergency().y,
                layout.emergency().width, layout.emergency().height, RED);
    }

    private static void door(GuiGraphics graphics, Font font, Box box, VehicleDoor side, float partialTick,
                             double mouseX, double mouseY) {
        var vehicle = TrainDrivingClient.riddenVehicle();
        boolean open = vehicle.isDoorOpen(side);
        panel(graphics, box, box.contains(mouseX, mouseY) ? 0xFF293E4A : PANEL);
        int color = open ? AMBER : GREEN;
        var characters = text(side == VehicleDoor.LEFT ? "left_door" : "right_door").getString().codePoints().toArray();
        int y = box.y + (box.height - characters.length * 8) / 2;
        for (int character : characters) {
            var glyph = Component.literal(Character.toString(character));
            int glyphWidth = (int) Math.ceil(font.width(glyph) * TEXT_SCALE);
            label(graphics, font, glyph, box.x + (box.width - glyphWidth) / 2, y, glyphWidth, color);
            y += 8;
        }
        int barX = side == VehicleDoor.LEFT ? box.x + 2 : box.x + box.width - 4;
        graphics.fill(barX, box.y + 2, barX + 2, box.y + box.height - 2, EDGE);
        int progress = Math.round((box.height - 4) * vehicle.doorMovement(side, partialTick));
        graphics.fill(barX, box.y + box.height - 2 - progress, barX + 2, box.y + box.height - 2, AMBER);
    }

    private static void button(GuiGraphics graphics, Font font, Box box, Component title, boolean enabled,
                               int color, double mouseX, double mouseY) {
        panel(graphics, box, enabled && box.contains(mouseX, mouseY) ? 0xFF293E4A : 0xFF1A2834);
        graphics.fill(box.x + 3, box.y + 4, box.x + 5, box.y + box.height - 4, enabled ? color : EDGE);
        label(graphics, font, title, box.x + 9, box.y + (box.height - (int) Math.ceil(font.lineHeight * TEXT_SCALE)) / 2,
                box.width - 13, enabled ? TEXT : MUTED);
    }

    private static void panel(GuiGraphics graphics, Box box, int color) {
        graphics.fill(box.x, box.y, box.x + box.width, box.y + box.height, color);
        graphics.renderOutline(box.x, box.y, box.width, box.height, EDGE);
    }

    private static void label(GuiGraphics graphics, Font font, Component value, int x, int y, int width, int color) {
        String visible = font.plainSubstrByWidth(value.getString(), Math.max(0, (int) (width / TEXT_SCALE)));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1);
        graphics.drawString(font, visible, 0, 0, color, false);
        graphics.pose().popPose();
    }

    private static void tooltip(GuiGraphics graphics, Font font, Layout layout, TrainControlSyncPayload state,
                                float partialTick, double mouseX, double mouseY) {
        if (mouseX < 0 || mouseY < 0) return;
        Component tip = null;
        if (layout.speed.contains(mouseX, mouseY) || layout.data.contains(mouseX, mouseY)) {
            tip = Component.empty().append(motion).append("\n")
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
        } else if (layout.leftDoor().contains(mouseX, mouseY)) {
            tip = doorHint(VehicleDoor.LEFT, partialTick);
        } else if (layout.rightDoor().contains(mouseX, mouseY)) {
            tip = doorHint(VehicleDoor.RIGHT, partialTick);
        } else if (layout.desk.contains(mouseX, mouseY)) {
            tip = Component.empty().append(condition).append("\n").append(TrainDrivingClient.cursorHint()).append("\n").append(controls);
        }
        if (tip != null) graphics.renderTooltip(font, font.split(tip,
                Math.min(320, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 16)), (int) mouseX, (int) mouseY);
    }

    private static Component doorHint(VehicleDoor side, float partialTick) {
        var vehicle = TrainDrivingClient.riddenVehicle();
        return text("door_hint", text(vehicle.isDoorOpen(side) ? "close" : "open"),
                Math.round(vehicle.doorMovement(side, partialTick) * 100));
    }

    public static void click(double mouseX, double mouseY) {
        var state = TrainDrivingClient.state();
        var layout = layout();
        if (!state.emergency() && mouseY >= layout.handle.y + HANDLE_TOP - 3
                && (layout.power().contains(mouseX, mouseY) || layout.brake().contains(mouseX, mouseY))) {
            draggingPower = layout.power().contains(mouseX, mouseY);
            drag(mouseY);
            return;
        }
        Action action = null;
        if (layout.reverser().contains(mouseX, mouseY) && state.speed() == 0
                && state.powerNotch() == 0 && state.brakeNotch() == 0) action = Action.REVERSER_NEXT;
        else if (layout.emergency().contains(mouseX, mouseY) && (!state.emergency() || state.speed() == 0)) {
            action = state.emergency() ? Action.EMERGENCY_RELEASE : Action.EMERGENCY_APPLY;
        } else if (layout.leftDoor().contains(mouseX, mouseY)) {
            action = TrainDrivingClient.riddenVehicle().isDoorOpen(VehicleDoor.LEFT) ? Action.DOOR_LEFT_CLOSE : Action.DOOR_LEFT_OPEN;
        } else if (layout.rightDoor().contains(mouseX, mouseY)) {
            action = TrainDrivingClient.riddenVehicle().isDoorOpen(VehicleDoor.RIGHT) ? Action.DOOR_RIGHT_CLOSE : Action.DOOR_RIGHT_OPEN;
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
        int target = (int) Math.round(Math.clamp((box.y + HANDLE_BOTTOM - mouseY) / (HANDLE_BOTTOM - HANDLE_TOP), 0, 1) * count);
        if (target != requestedNotch) {
            requestedNotch = target;
            TrainDrivingClient.send(power ? Action.POWER_SET : Action.BRAKE_SET, target);
        }
    }

    public static void release() {
        draggingPower = null;
        requestedNotch = -1;
    }

    public static void clear() {
        displayed = null;
        language = null;
        values = null;
        release();
    }
}
