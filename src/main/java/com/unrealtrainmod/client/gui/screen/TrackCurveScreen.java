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
import com.unrealtrainmod.track.TrackVerticalCurveParameters;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Both independent pages edit one reversible local draft and are submitted together. */
public final class TrackCurveScreen extends Screen {
    private final OpenTrackCurvePayload open;
    private AngleSlider angle;
    private Button angleDecreaseLarge, angleDecreaseSmall, angleIncreaseSmall, angleIncreaseLarge;
    private Button horizontalTab, verticalTab;
    private EditBox length, grade, verticalLength;
    private TrainIconButton apply, reset;
    private boolean verticalPage, automatic, verticalAutomatic, pending, updating;
    private boolean horizontalEdited, verticalEdited, horizontalValid = true, verticalValid = true;
    private TrackCurveParameters parsed;
    private TrackVerticalCurveParameters parsedVertical;
    private Component status;
    private int left, top, panelWidth, panelHeight;

    public TrackCurveScreen(OpenTrackCurvePayload open) {
        super(label("title"));
        this.open = open;
        automatic = open.automatic();
        verticalAutomatic = open.verticalAutomatic();
        parsed = open.initial();
        parsedVertical = open.initialVertical();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(216, Math.max(150, width / 3));
        panelHeight = Math.min(272, height - 12);
        left = 6;
        top = Math.max(6, (height - panelHeight) / 2);
        double angleValue = angle == null ? parsed.angle() : angle.number();
        String lengthText = length == null ? Double.toString(parsed.length()) : length.getValue();
        String gradeText = grade == null ? Double.toString(parsedVertical.grade()) : grade.getValue();
        String verticalLengthText = verticalLength == null ? Double.toString(parsedVertical.length()) : verticalLength.getValue();
        int tabWidth = (panelWidth - 22) / 2;
        horizontalTab = addRenderableWidget(Button.builder(label("horizontal"), button -> selectPage(false))
                .bounds(left + 10, top + 38, tabWidth, 18).build());
        verticalTab = addRenderableWidget(Button.builder(label("vertical"), button -> selectPage(true))
                .bounds(left + 12 + tabWidth, top + 38, tabWidth, 18).build());
        angle = new AngleSlider(left + 50, top + 78, panelWidth - 100, angleValue, value -> horizontalChanged());
        angleDecreaseLarge = angleButton(left + 10, "<<", "angle_decrease_large", 15, false);
        angleDecreaseSmall = angleButton(left + 30, "<", "angle_decrease_small", 1, false);
        angleIncreaseSmall = angleButton(angle.getRight() + 2, ">", "angle_increase_small", 1, true);
        angleIncreaseLarge = angleButton(angle.getRight() + 22, ">>", "angle_increase_large", 15, true);
        length = field(top + 115, "length", lengthText);
        grade = field(top + 78, "grade", gradeText);
        verticalLength = field(top + 115, "vertical_length", verticalLengthText);
        apply = new TrainIconButton(left + 10, top + panelHeight - 74, panelWidth - 20,
                label("apply"), TrainIconButton.Icon.CONFIRM, this::submit);
        reset = new TrainIconButton(left + 10, top + panelHeight - 50, panelWidth - 20,
                label("automatic"), TrainIconButton.Icon.RESET, this::resetAutomatic);
        var cancel = new TrainIconButton(left + 10, top + panelHeight - 26, panelWidth - 20,
                CommonComponents.GUI_CANCEL, TrainIconButton.Icon.CLOSE, this::onClose);
        length.setResponder(value -> horizontalChanged());
        grade.setResponder(value -> verticalChanged());
        verticalLength.setResponder(value -> verticalChanged());
        addRenderableWidget(angleDecreaseLarge);
        addRenderableWidget(angleDecreaseSmall);
        addRenderableWidget(angle);
        addRenderableWidget(angleIncreaseSmall);
        addRenderableWidget(angleIncreaseLarge);
        addRenderableWidget(length);
        addRenderableWidget(grade);
        addRenderableWidget(verticalLength);
        addRenderableWidget(apply);
        addRenderableWidget(reset);
        addRenderableWidget(cancel);
        updateWidgets();
    }

    private EditBox field(int y, String key, String value) {
        var field = new EditBox(font, left + 10, y, panelWidth - 20, 18, label(key));
        field.setMaxLength(32);
        field.setValue(value);
        return field;
    }

    private static Component label(String key) {
        return Component.translatable("gui.unrealtrainmod.track_curve." + key);
    }

    private Button angleButton(int x, String text, String key, int step, boolean increase) {
        Component description = label(key);
        return Button.builder(Component.literal(text), button -> angle.adjust(step, increase))
                .bounds(x, top + 78, 18, 18).tooltip(Tooltip.create(description))
                .createNarration(supplier -> description.copy()).build();
    }

    private void selectPage(boolean vertical) {
        verticalPage = vertical;
        setFocused(null);
        updateWidgets();
    }

    private void horizontalChanged() {
        if (updating) return;
        automatic = false;
        horizontalEdited = true;
        status = null;
        try {
            parsed = new TrackCurveParameters(angle.number(), Double.parseDouble(length.getValue()));
            horizontalValid = true;
            preview();
        } catch (IllegalArgumentException exception) {
            horizontalValid = false;
        }
        updateWidgets();
    }

    private void verticalChanged() {
        if (updating) return;
        verticalAutomatic = false;
        verticalEdited = true;
        status = null;
        try {
            parsedVertical = new TrackVerticalCurveParameters(open.verticalLocked() ? 0 : Double.parseDouble(grade.getValue()),
                    Double.parseDouble(verticalLength.getValue()), parsedVertical.referenceYaw());
            verticalValid = true;
            preview();
        } catch (IllegalArgumentException exception) {
            verticalValid = false;
        }
        updateWidgets();
    }

    private void preview() {
        var state = ClientTrackConstruction.INSTANCE;
        state.editPreview(open.start(), automatic ? null : parsed, verticalAutomatic ? null : parsedVertical,
                horizontalEdited, verticalEdited);
        var tangent = state.endpointTangent(open.start(), open.initialVertical().referenceYaw());
        if (!verticalEdited || verticalAutomatic) {
            var effective = state.previewVertical(open.start());
            parsedVertical = effective == null ? state.defaultVerticalParameters(open.start(), open.initialVertical().referenceYaw())
                    : new TrackVerticalCurveParameters(effective.gradeAlong(tangent), effective.length(),
                            TrackVerticalCurveParameters.yaw(tangent));
            updating = true;
            grade.setValue(Double.toString(parsedVertical.grade()));
            verticalLength.setValue(Double.toString(parsedVertical.length()));
            updating = false;
        }
    }

    private void resetAutomatic() {
        updating = true;
        if (verticalPage) {
            parsedVertical = open.initialVertical();
            grade.setValue(Double.toString(parsedVertical.grade()));
            verticalLength.setValue(Double.toString(parsedVertical.length()));
            verticalAutomatic = !open.verticalLocked();
            verticalEdited = true;
            verticalValid = true;
        } else {
            angle.setNumber(open.initial().angle());
            length.setValue(Double.toString(open.initial().length()));
            parsed = open.initial();
            automatic = true;
            horizontalEdited = true;
            horizontalValid = true;
        }
        updating = false;
        status = null;
        preview();
        updateWidgets();
    }

    private void updateWidgets() {
        apply.active = horizontalValid && verticalValid && !pending;
        reset.active = !pending;
        horizontalTab.active = !pending && verticalPage;
        verticalTab.active = !pending && !verticalPage;
        angle.visible = !verticalPage;
        angleDecreaseLarge.visible = !verticalPage;
        angleDecreaseSmall.visible = !verticalPage;
        angleIncreaseSmall.visible = !verticalPage;
        angleIncreaseLarge.visible = !verticalPage;
        length.setVisible(!verticalPage);
        grade.setVisible(verticalPage);
        verticalLength.setVisible(verticalPage);
        angle.active = !pending;
        angleDecreaseLarge.active = !pending;
        angleDecreaseSmall.active = !pending;
        angleIncreaseSmall.active = !pending;
        angleIncreaseLarge.active = !pending;
        length.setEditable(!pending);
        grade.setEditable(!pending && !open.verticalLocked());
        verticalLength.setEditable(!pending);
    }

    private void submit() {
        if (!apply.active) return;
        pending = true;
        status = label("pending");
        updateWidgets();
        PacketDistributor.sendToServer(new SetTrackCurvePayload(open.dimension(), open.revision(), open.editorId(),
                open.start(), horizontalEdited, verticalEdited, automatic, parsed.angle(), parsed.length(),
                verticalAutomatic, parsedVertical.grade(), parsedVertical.length()));
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
                || (!pending && !ClientTrackConstruction.INSTANCE.matches(open.dimension(), open.revision()))) onClose();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.panel(graphics, left, top, panelWidth, panelHeight, GuiTheme.BACKGROUND);
        drawLabel(graphics, title, 10, GuiTheme.TEXT);
        drawLabel(graphics, label(open.start() ? "start" : "end"), 25, GuiTheme.ACCENT);
        drawLabel(graphics, label(verticalPage ? "grade" : "angle"), 65, GuiTheme.TEXT);
        drawLabel(graphics, label(verticalPage ? "vertical_length" : "length"), 102, GuiTheme.TEXT);
        boolean auto = verticalPage ? verticalAutomatic : automatic;
        drawLabel(graphics, label(auto ? "mode_auto" : "mode_curve"), 140, GuiTheme.MUTED);
        int y = top + 155;
        for (var line : font.split(message(), panelWidth - 20)) {
            if (y + font.lineHeight > top + panelHeight - 80) break;
            graphics.drawString(font, line, left + 10, y,
                    !horizontalValid || !verticalValid || (status != null && !pending) ? GuiTheme.ERROR : GuiTheme.MUTED, false);
            y += font.lineHeight;
        }
    }

    private void drawLabel(GuiGraphics graphics, Component label, int y, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(label.getString(), panelWidth - 20), left + 10, top + y, color, false);
    }

    private Component message() {
        if (status != null) return status;
        if (!horizontalValid || !verticalValid) return Component.translatable(Feedback.INVALID_PARAMETERS.translationKey());
        Feedback feedback = ClientTrackConstruction.INSTANCE.previewFeedback();
        if (feedback != null && feedback != Feedback.START && feedback != Feedback.READY) {
            return Component.translatable(feedback.translationKey());
        }
        return label(verticalPage ? open.verticalLocked() ? "flat_port_hint" : "vertical_hint" : "hint");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= top + 140
                && mouseY < top + panelHeight - 78) graphics.renderTooltip(font, message(), mouseX, mouseY);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        ClientTrackConstruction.INSTANCE.restorePreview();
        super.removed();
    }
}
