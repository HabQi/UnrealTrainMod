/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.screen;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.client.gui.widget.AngleSlider;
import com.unrealtrainmod.client.track.ClientTurnoutConstruction;
import com.unrealtrainmod.network.payload.OpenTurnoutPayload;
import com.unrealtrainmod.network.payload.TurnoutActionPayload;
import com.unrealtrainmod.network.payload.TurnoutResultPayload;
import com.unrealtrainmod.network.payload.TurnoutResultPayload.Feedback;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Fixed-origin side panel; geometry changes immediately, approval belongs to the latest server result. */
public final class TurnoutConstructionScreen extends Screen {
    private final OpenTurnoutPayload open;
    private TrackAssemblyDefinition definition;
    private double yaw;
    private AngleSlider angle;
    private EditBox yawBox;
    private Button side;
    private Button confirm;
    private Feedback feedback;
    private String detail;
    private long sequence;
    private long approvedSequence;
    private int debounce;
    private int panelWidth;
    private int panelHeight;
    private boolean valid = true;
    private boolean waiting;
    private boolean confirming;
    private boolean finished;
    private boolean initializing;

    public TurnoutConstructionScreen(OpenTurnoutPayload open) {
        super(label("title")); this.open = open; definition = open.definition(); yaw = open.yaw();
        feedback = open.feedback(); detail = open.detail(); approvedSequence = feedback == Feedback.READY ? 0 : -1;
    }
    private static Component label(String key, Object... values) {
        return Component.translatable("gui.unrealtrainmod.turnout." + key, values);
    }
    @Override protected void init() {
        initializing = true;
        panelWidth = Math.min(232, width - 12); panelHeight = Math.min(290, height - 12);
        String text = yawBox == null ? Double.toString(yaw) : yawBox.getValue();
        side = Button.builder(sideLabel(), b -> {
            definition = definition == TrackAssemblyDefinition.LEFT ? TrackAssemblyDefinition.RIGHT : TrackAssemblyDefinition.LEFT;
            side.setMessage(sideLabel()); changed();
        }).bounds(16, 40, panelWidth - 20, 20).build();
        angle = new AngleSlider(16, 77, panelWidth - 20, yaw, value -> {
            yawBox.setValue(Double.toString(value));
        });
        yawBox = new EditBox(font, 16, 99, panelWidth - 20, 18, label("yaw"));
        yawBox.setMaxLength(32); yawBox.setValue(text);
        yawBox.setResponder(value -> {
            if (initializing) return;
            try {
                double parsed = Double.parseDouble(value);
                valid = Double.isFinite(parsed) && parsed >= -180 && parsed <= 180;
                if (valid) { yaw = parsed; angle.setNumber(yaw); }
            } catch (NumberFormatException exception) { valid = false; }
            changed();
        });
        confirm = Button.builder(label("confirm"), b -> submit()).bounds(16, 6 + panelHeight - 26,
                (panelWidth - 24) / 2, 20).build();
        var cancel = Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(20 + (panelWidth - 24) / 2, 6 + panelHeight - 26, (panelWidth - 24) / 2, 20).build();
        addRenderableWidget(side); addRenderableWidget(angle); addRenderableWidget(yawBox);
        addRenderableWidget(confirm); addRenderableWidget(cancel);
        initializing = false; updateWidgets();
    }
    private Component sideLabel() {
        return Component.translatable("commands.unrealtrainmod.turnout." + definition.name().toLowerCase(Locale.ROOT));
    }
    private void changed() {
        sequence++; approvedSequence = -1; waiting = valid; debounce = valid ? 4 : 0;
        feedback = valid ? null : Feedback.INVALID_PARAMETERS; detail = "";
        ClientTurnoutConstruction.INSTANCE.update(definition, yaw, feedback);
        updateWidgets();
    }
    private void updateWidgets() {
        if (confirm == null) return;
        confirm.active = valid && !waiting && !confirming && feedback == Feedback.READY && approvedSequence == sequence;
        side.active = !confirming;
        angle.active = !open.locked() && !confirming;
        yawBox.setEditable(!open.locked() && !confirming);
    }
    private void send(TurnoutActionPayload.Action action) {
        PacketDistributor.sendToServer(new TurnoutActionPayload(open.dimension(), open.draftId(), sequence,
                action, definition, yaw));
    }
    private void submit() {
        if (!confirm.active) return;
        confirming = true; waiting = true; sequence++;
        ClientTurnoutConstruction.INSTANCE.update(definition, yaw, null);
        updateWidgets(); send(TurnoutActionPayload.Action.CONFIRM);
    }
    public void accept(TurnoutResultPayload result) {
        if (!open.draftId().equals(result.draftId()) || !open.dimension().equals(result.dimension())) return;
        if (!result.closed() && result.sequence() != sequence) return;
        if (result.closed()) {
            finished = true;
            if (minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.translatable(result.feedback().translationKey(), result.detail()).withStyle(
                            result.feedback() == Feedback.CREATED || result.feedback() == Feedback.CANCELLED
                                    ? ChatFormatting.GREEN : ChatFormatting.RED), true);
            minecraft.setScreen(null); return;
        }
        waiting = false; confirming = false; feedback = result.feedback(); detail = result.detail();
        approvedSequence = feedback == Feedback.READY ? sequence : -1;
        ClientTurnoutConstruction.INSTANCE.update(definition, yaw, feedback); updateWidgets();
    }
    @Override public void tick() {
        var player = minecraft.player;
        if (player == null || !player.isAlive() || !player.hasPermissions(2) || minecraft.level == null
                || !ClientTurnoutConstruction.INSTANCE.matches(open.draftId())
                || !minecraft.level.dimension().location().equals(open.dimension())
                || !player.getMainHandItem().is(UnrealTrainMod.TURNOUT_BUILDER.get())) {
            onClose(); return;
        }
        if (debounce > 0 && --debounce == 0) send(TurnoutActionPayload.Action.UPDATE);
    }
    private Component status() {
        return waiting ? label(confirming ? "placing" : "pending")
                : feedback == null ? label("pending") : Component.translatable(feedback.translationKey(), detail);
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        GuiTheme.panel(g, 6, 6, panelWidth, panelHeight, GuiTheme.BACKGROUND);
        text(g, title, 16, GuiTheme.TEXT);
        text(g, open.origin().nodeId() == null ? label("new_origin")
                : label("snapped", open.origin().nodeId().toString()), 29, GuiTheme.MUTED);
        text(g, label(open.locked() ? "yaw_locked" : "yaw"), 65, GuiTheme.TEXT);
        text(g, label("gauge", number(open.gauge())), 124, GuiTheme.TEXT);
        text(g, label("dimensions", number(32 * open.gauge() / 1.435), number(Math.toDegrees(TrackAssemblyDefinition.ANGLE))),
                137, GuiTheme.MUTED);
        text(g, label("ports"), 150, GuiTheme.ACCENT);
        int y = 170;
        for (var line : font.split(status(), panelWidth - 20)) {
            if (y + font.lineHeight > panelHeight - 24) break;
            g.drawString(font, line, 16, y, feedback == Feedback.READY && !waiting ? 0xFF80E080
                    : waiting ? GuiTheme.MUTED : GuiTheme.ERROR, false);
            y += font.lineHeight;
        }
    }
    private void text(GuiGraphics g, Component text, int y, int color) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), panelWidth - 20), 16, y, color, false);
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (mouseX >= 6 && mouseX < 6 + panelWidth && mouseY >= 163 && mouseY < panelHeight - 24)
            g.renderTooltip(font, status(), mouseX, mouseY);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        if (!finished && minecraft.getConnection() != null && minecraft.level != null
                && minecraft.level.dimension().location().equals(open.dimension())) {
            sequence++; send(TurnoutActionPayload.Action.CANCEL);
        }
        ClientTurnoutConstruction.INSTANCE.clear();
        super.removed();
    }
}
