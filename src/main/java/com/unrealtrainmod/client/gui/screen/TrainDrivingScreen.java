/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.screen;

import com.unrealtrainmod.client.TrainDrivingClient;
import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.network.payload.TrainControlInputPayload.Action;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import com.unrealtrainmod.train.TrainControlProfile;
import com.unrealtrainmod.vehicle.VehicleDoor;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Non-pausing cab controls. Values and button states follow server snapshots, never local predictions. */
public final class TrainDrivingScreen extends Screen {
    private final UUID session;
    private int left, top, panelWidth, columnWidth, rightColumn;
    private Button power, brake, reverser, emergency, leftDoor, rightDoor;
    private TrainControlSyncPayload displayed;
    private boolean leftOpen, rightOpen;
    private String speed;
    private Component condition, motionDirection, handle;
    private Component[] details;

    public TrainDrivingScreen(UUID session) {
        super(text("title"));
        this.session = session;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("gui.unrealtrainmod.driving.panel." + key, args);
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    @Override protected void init() {
        panelWidth = Math.min(440, width - 12);
        left = (width - panelWidth) / 2;
        top = (height - 228) / 2;
        columnWidth = (panelWidth - 30) / 2;
        rightColumn = left + 20 + columnWidth;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + panelWidth - 68, top + 5, 58, 20).build());
        int half = (columnWidth - 4) / 2;
        brake = action("brake", Action.HANDLE_DOWN, left + 10, top + 136, half);
        power = action("power", Action.HANDLE_UP, left + 14 + half, top + 136, columnWidth - half - 4);
        reverser = action("reverser", Action.REVERSER_NEXT, rightColumn, top + 136, columnWidth);
        reverser.setTooltip(Tooltip.create(Component.translatable("gui.unrealtrainmod.driving.reverser_locked")));
        // The target is captured in each displayed button, just like an explicit door-open/close request.
        emergency = addRenderableWidget(Button.builder(text("emergency_apply"), button -> request(
                displayed.emergency() ? Action.EMERGENCY_RELEASE : Action.EMERGENCY_APPLY))
                .bounds(rightColumn, top + 160, columnWidth, 20).build());
        leftDoor = addRenderableWidget(Button.builder(text("left_open"), button -> request(
                leftOpen ? Action.DOOR_LEFT_CLOSE : Action.DOOR_LEFT_OPEN))
                .bounds(left + 10, top + 188, columnWidth, 20).build());
        rightDoor = addRenderableWidget(Button.builder(text("right_open"), button -> request(
                rightOpen ? Action.DOOR_RIGHT_CLOSE : Action.DOOR_RIGHT_OPEN))
                .bounds(rightColumn, top + 188, columnWidth, 20).build());
        leftDoor.active = rightDoor.active = false;
        displayed = null;
        refresh();
    }

    private Button action(String label, Action action, int x, int y, int buttonWidth) {
        return addRenderableWidget(Button.builder(text(label), button -> request(action))
                .bounds(x, y, buttonWidth, 20).build());
    }

    private TrainControlSyncPayload state() {
        var state = TrainDrivingClient.state();
        return state != null && state.session().equals(session) ? state : null;
    }

    private void request(Action action) {
        if (state() != null) TrainDrivingClient.send(action);
    }

    @Override public void tick() {
        if (state() == null) { onClose(); return; }
        refresh();
    }

    private void refresh() {
        var state = state();
        boolean available = state != null;
        if (!available) {
            displayed = null;
            power.active = brake.active = reverser.active = emergency.active = leftDoor.active = rightDoor.active = false;
            return;
        }
        if (displayed != state) {
            displayed = state;
            speed = number(Math.abs(state.speed()) * 3.6);
            condition = TrainDrivingClient.condition(state);
            motionDirection = text("motion_direction", text(state.speed() > 0 ? "toward_front"
                    : state.speed() < 0 ? "toward_back" : "at_rest"));
            handle = text("handle", state.emergency() ? "EB" : TrainControlProfile.notch(state.handle()));
            Component demand = state.emergency() ? Component.translatable("gui.unrealtrainmod.driving.brake_emergency")
                    : Component.literal(number(Math.max(0, -state.handle()) * 100.0 / TrainControlProfile.BRAKE_NOTCHES) + "%");
            details = new Component[] {
                Component.translatable("gui.unrealtrainmod.driving.motion", number(state.acceleration()), number(state.grade() * 1000)),
                Component.translatable("gui.unrealtrainmod.driving.traction", number(state.tractionForce() / 1000), number(state.tractionPower() / 1000)),
                Component.translatable("gui.unrealtrainmod.driving.brake", demand, number(state.brakeForce() / 1000))
            };
            power.active = !state.emergency() && state.handle() < TrainControlProfile.POWER_NOTCHES;
            brake.active = !state.emergency() && state.handle() > -TrainControlProfile.BRAKE_NOTCHES;
            reverser.active = state.speed() == 0 && state.handle() == 0;
            reverser.setMessage(text("reverser_value", TrainDrivingClient.direction(state)));
            emergency.active = !state.emergency() || state.speed() == 0;
            emergency.setMessage(text(state.emergency() ? "emergency_release" : "emergency_apply"));
            emergency.setTooltip(Tooltip.create(text(state.emergency() ? "emergency_release_hint" : "emergency_apply_hint")));
        }
        var vehicle = TrainDrivingClient.riddenVehicle();
        boolean newLeft = vehicle.isDoorOpen(VehicleDoor.LEFT), newRight = vehicle.isDoorOpen(VehicleDoor.RIGHT);
        if (!leftDoor.active || leftOpen != newLeft) leftDoor.setMessage(text(newLeft ? "left_close" : "left_open"));
        if (!rightDoor.active || rightOpen != newRight) rightDoor.setMessage(text(newRight ? "right_close" : "right_open"));
        leftOpen = newLeft;
        rightOpen = newRight;
        leftDoor.active = rightDoor.active = true;
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x66000000);
        GuiTheme.panel(graphics, left, top, panelWidth, 228, GuiTheme.BACKGROUND);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Entity door updates can arrive between client ticks; cache formatted telemetry per snapshot.
        refresh();
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left + 10, top + 11, GuiTheme.TEXT, false);
        var state = state();
        if (state == null) return;
        GuiTheme.panel(graphics, left + 10, top + 30, panelWidth - 20, 46, GuiTheme.PANEL);
        graphics.pose().pushPose();
        graphics.pose().translate(left + 18, top + 38, 0);
        graphics.pose().scale(2, 2, 1);
        graphics.drawString(font, speed, 0, 0, GuiTheme.ACCENT, false);
        graphics.pose().popPose();
        graphics.drawString(font, "km/h", left + 18, top + 62, GuiTheme.MUTED, false);
        line(graphics, condition, rightColumn, top + 38, columnWidth - 8,
                state.emergency() ? GuiTheme.ERROR : GuiTheme.ACCENT, mouseX, mouseY);
        line(graphics, motionDirection, rightColumn, top + 59,
                columnWidth - 8, GuiTheme.MUTED, mouseX, mouseY);
        for (int i = 0; i < details.length; i++) {
            line(graphics, details[i], left + 10, top + 82 + i * 12, panelWidth - 20, GuiTheme.MUTED, mouseX, mouseY);
        }
        graphics.drawString(font, handle,
                left + 10, top + 122, state.emergency() ? GuiTheme.ERROR : GuiTheme.TEXT, false);
        line(graphics, text("reverser"), rightColumn, top + 122, columnWidth, GuiTheme.TEXT, mouseX, mouseY);
        drawHandle(graphics, state);
        var vehicle = TrainDrivingClient.riddenVehicle();
        doorProgress(graphics, leftDoor, vehicle.doorMovement(VehicleDoor.LEFT, partialTick), leftOpen, mouseX, mouseY);
        doorProgress(graphics, rightDoor, vehicle.doorMovement(VehicleDoor.RIGHT, partialTick), rightOpen, mouseX, mouseY);
        line(graphics, text("footer"), left + 10, top + 217, panelWidth - 20, GuiTheme.MUTED, mouseX, mouseY);
    }

    private void drawHandle(GuiGraphics graphics, TrainControlSyncPayload state) {
        int count = TrainControlProfile.BRAKE_NOTCHES + TrainControlProfile.POWER_NOTCHES + 1;
        for (int i = 0; i < count; i++) {
            int notch = i - TrainControlProfile.BRAKE_NOTCHES;
            int x = left + 10 + i * columnWidth / count;
            int end = left + 10 + (i + 1) * columnWidth / count - 1;
            int tint = notch == state.handle() && !state.emergency() ? GuiTheme.ACCENT
                    : state.emergency() && notch < 0 ? GuiTheme.ERROR : GuiTheme.BORDER;
            graphics.fill(x, top + 162, end, top + 170, tint);
        }
        graphics.drawString(font, "B8", left + 10, top + 174, GuiTheme.MUTED, false);
        graphics.drawCenteredString(font, "N", left + 10 + 8 * columnWidth / count + columnWidth / count / 2,
                top + 174, GuiTheme.MUTED);
        graphics.drawString(font, "P5", left + 10 + columnWidth - font.width("P5"), top + 174, GuiTheme.MUTED, false);
    }

    private void doorProgress(GuiGraphics graphics, Button button, float progress, boolean open, int mouseX, int mouseY) {
        int x = button.getX(), y = button.getY() + button.getHeight() + 1;
        graphics.fill(x, y, x + button.getWidth(), y + 3, GuiTheme.BORDER);
        graphics.fill(x, y, x + Math.round(button.getWidth() * progress), y + 3, GuiTheme.ACCENT);
        if (open) graphics.renderOutline(x, button.getY(), button.getWidth(), button.getHeight(), GuiTheme.ACCENT);
        if (mouseX >= x && mouseX < x + button.getWidth() && mouseY >= button.getY() && mouseY < y + 3) {
            graphics.renderTooltip(font, font.split(text("door_status", text(open ? "door_open" : "door_closed"),
                    Math.round(progress * 100)), Math.min(300, width - 20)), mouseX, mouseY);
        }
    }

    private void line(GuiGraphics graphics, Component label, int x, int y, int lineWidth, int color, int mouseX, int mouseY) {
        graphics.drawString(font, font.plainSubstrByWidth(label.getString(), lineWidth), x, y, color, false);
        if (mouseX >= x && mouseX < x + lineWidth && mouseY >= y && mouseY < y + font.lineHeight) {
            graphics.renderTooltip(font, font.split(label, Math.min(360, width - 20)), mouseX, mouseY);
        }
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (state() == null) { onClose(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || TrainDrivingClient.panelKey(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (TrainDrivingClient.isControlKey(keyCode, scanCode)) return false;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean isPauseScreen() { return false; }
}
