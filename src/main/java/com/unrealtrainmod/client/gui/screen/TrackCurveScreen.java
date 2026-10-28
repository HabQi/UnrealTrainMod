/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.screen;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.client.gui.widget.AngleSlider;
import com.unrealtrainmod.client.gui.widget.TrainIconButton;
import com.unrealtrainmod.client.track.ClientTrackConstruction;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload;
import com.unrealtrainmod.network.payload.SetTrackCurvePayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.network.payload.TrackCurveResultPayload;
import com.unrealtrainmod.track.TrackCurveParameters;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** A small non-pausing panel; the world remains visible behind the local, reversible edit. */
public final class TrackCurveScreen extends Screen {
    private final OpenTrackCurvePayload open;
    private AngleSlider angle;
    private Button angleDecreaseLarge;
    private Button angleDecreaseSmall;
    private Button angleIncreaseSmall;
    private Button angleIncreaseLarge;
    private EditBox length;
    private TrainIconButton apply;
    private TrainIconButton reset;
    private boolean automatic;
    private boolean pending;
    private boolean valid = true;
    private TrackCurveParameters parsed;
    private Component status;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;

    public TrackCurveScreen(OpenTrackCurvePayload open) {
        super(Component.translatable("gui.unrealtrainmod.track_curve.title"));
        this.open = open;
        automatic = open.automatic();
        parsed = open.initial();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(216, Math.max(150, width / 3));
        panelHeight = Math.min(248, height - 12);
        left = 6;
        top = Math.max(6, (height - panelHeight) / 2);
        double angleValue = angle == null ? open.initial().angle() : angle.number();
        String lengthText = length == null ? Double.toString(open.initial().length()) : length.getValue();
        angle = new AngleSlider(left + 50, top + 55, panelWidth - 100, angleValue, value -> changed());
        angleDecreaseLarge = angleButton(left + 10, "<<", "angle_decrease_large", 15, false);
        angleDecreaseSmall = angleButton(left + 30, "<", "angle_decrease_small", 1, false);
        angleIncreaseSmall = angleButton(angle.getRight() + 2, ">", "angle_increase_small", 1, true);
        angleIncreaseLarge = angleButton(angle.getRight() + 22, ">>", "angle_increase_large", 15, true);
        length = new EditBox(font, left + 10, top + 92, panelWidth - 20, 18, label("length"));
        length.setMaxLength(32);
        length.setValue(lengthText);
        apply = new TrainIconButton(left + 10, top + panelHeight - 74, panelWidth - 20,
                label("apply"), TrainIconButton.Icon.CONFIRM, this::submit);
        reset = new TrainIconButton(left + 10, top + panelHeight - 50, panelWidth - 20,
                label("automatic"), TrainIconButton.Icon.RESET, this::resetAutomatic);
        var cancel = new TrainIconButton(left + 10, top + panelHeight - 26, panelWidth - 20,
                CommonComponents.GUI_CANCEL, TrainIconButton.Icon.CLOSE, this::onClose);
        length.setResponder(value -> changed());
        addRenderableWidget(angleDecreaseLarge);
        addRenderableWidget(angleDecreaseSmall);
        addRenderableWidget(angle);
        addRenderableWidget(angleIncreaseSmall);
        addRenderableWidget(angleIncreaseLarge);
        addRenderableWidget(length);
        addRenderableWidget(apply);
        addRenderableWidget(reset);
        addRenderableWidget(cancel);
        updateWidgets();
    }

    private static Component label(String key) {
        return Component.translatable("gui.unrealtrainmod.track_curve." + key);
    }

    private Button angleButton(int x, String text, String key, int step, boolean increase) {
        Component description = label(key);
        return Button.builder(Component.literal(text), button -> angle.adjust(step, increase))
                .bounds(x, top + 55, 18, 18)
                .tooltip(Tooltip.create(description))
                .createNarration(supplier -> description.copy())
                .build();
    }

    private void changed() {
        automatic = false;
        status = null;
        try {
            parsed = new TrackCurveParameters(angle.number(), Double.parseDouble(length.getValue()));
            valid = true;
            ClientTrackConstruction.INSTANCE.editPreview(open.start(), parsed);
        } catch (IllegalArgumentException exception) {
            valid = false;
            status = Component.translatable(Feedback.INVALID_PARAMETERS.translationKey());
        }
        updateWidgets();
    }

    private void resetAutomatic() {
        angle.setNumber(open.initial().angle());
        length.setValue(Double.toString(open.initial().length()));
        automatic = true;
        valid = true;
        parsed = open.initial();
        status = null;
        ClientTrackConstruction.INSTANCE.editPreview(open.start(), null);
        updateWidgets();
    }

    private void updateWidgets() {
        apply.active = valid && !pending;
        reset.active = !pending;
        angle.active = !pending;
        angleDecreaseLarge.active = !pending;
        angleDecreaseSmall.active = !pending;
        angleIncreaseSmall.active = !pending;
        angleIncreaseLarge.active = !pending;
        length.setEditable(!pending);
    }

    private void submit() {
        if (!apply.active) return;
        pending = true;
        status = label("pending");
        updateWidgets();
        PacketDistributor.sendToServer(new SetTrackCurvePayload(open.dimension(), open.revision(), open.editorId(),
                open.start(), automatic, parsed.angle(), parsed.length()));
    }

    public void acceptResult(TrackCurveResultPayload result) {
        if (!pending || !open.editorId().equals(result.editorId())) return;
        pending = false;
        ClientTrackConstruction.INSTANCE.restorePreview();
        if (result.accepted()) {
            onClose();
            return;
        }
        status = Component.translatable(result.feedback().translationKey());
        updateWidgets();
    }

    @Override
    public void tick() {
        var player = minecraft.player;
        if (player == null || !player.isAlive() || !player.hasPermissions(2)
                || !player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())
                || minecraft.level == null || !minecraft.level.dimension().location().equals(open.dimension())
                || (!pending && !ClientTrackConstruction.INSTANCE.matches(open.dimension(), open.revision()))) {
            onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.panel(graphics, left, top, panelWidth, panelHeight, GuiTheme.BACKGROUND);
        drawLabel(graphics, title, 10, GuiTheme.TEXT);
        drawLabel(graphics, label(open.start() ? "start" : "end"), 25, GuiTheme.ACCENT);
        drawLabel(graphics, label("angle"), 42, GuiTheme.TEXT);
        drawLabel(graphics, label("length"), 79, GuiTheme.TEXT);
        drawLabel(graphics, label(automatic ? "mode_auto" : "mode_curve"), 117, GuiTheme.MUTED);
        int availableHeight = panelHeight - 210;
        int y = top + 132;
        for (var line : font.split(message(), panelWidth - 20)) {
            if (y + font.lineHeight > top + 132 + availableHeight) break;
            graphics.drawString(font, line, left + 10, y, !valid || (status != null && !pending) ? GuiTheme.ERROR : GuiTheme.MUTED, false);
            y += font.lineHeight;
        }
    }

    private void drawLabel(GuiGraphics graphics, Component label, int y, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(label.getString(), panelWidth - 20), left + 10, top + y, color, false);
    }

    private Component message() {
        if (status != null) return status;
        Feedback feedback = ClientTrackConstruction.INSTANCE.previewFeedback();
        return feedback != null && feedback != Feedback.START && feedback != Feedback.READY
                ? Component.translatable(feedback.translationKey()) : label("hint");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= top + 132
                && mouseY < top + panelHeight - 78) {
            graphics.renderTooltip(font, message(), mouseX, mouseY);
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        ClientTrackConstruction.INSTANCE.restorePreview();
        super.removed();
    }
}
