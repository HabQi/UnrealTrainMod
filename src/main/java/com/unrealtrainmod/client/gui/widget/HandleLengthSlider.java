/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.widget;

import com.unrealtrainmod.track.TrackCurveParameters;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Equally spaced length marks; supplied values stay precise until the user moves the slider. */
public final class HandleLengthSlider extends AbstractSliderButton {
    private static final double[] MARKS = createMarks();
    private final DoubleConsumer onChange;
    private double number;

    public HandleLengthSlider(int x, int y, int width, double current, DoubleConsumer onChange) {
        super(x, y, width, 18, Component.empty(), 0);
        this.onChange = onChange;
        setNumber(current);
    }

    private static double[] createMarks() {
        double[] marks = new double[44];
        int index = 0;
        for (int tenth = 1; tenth <= 10; tenth++) marks[index++] = tenth / 10.0;
        for (int length = 2; length <= 10; length++) marks[index++] = length;
        for (int length = 20; length <= 250; length += 10) marks[index++] = length;
        marks[index] = TrackCurveParameters.MAX_LENGTH;
        return marks;
    }

    public void setNumber(double number) {
        this.number = number;
        int index = Arrays.binarySearch(MARKS, number);
        double position;
        if (index >= 0) {
            position = index;
        } else {
            int upper = -index - 1;
            position = upper == 0 ? 0
                    : upper - 1 + (number - MARKS[upper - 1]) / (MARKS[upper] - MARKS[upper - 1]);
        }
        value = position / (MARKS.length - 1);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable("gui.unrealtrainmod.track_curve.length_value",
                BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()));
    }

    @Override
    protected void applyValue() {
        selectMark((int)Math.round(value * (MARKS.length - 1)));
    }

    private void selectMark(int index) {
        setNumber(MARKS[Math.clamp(index, 0, MARKS.length - 1)]);
        onChange.accept(number);
    }

    private void setFromMouse(double mouseX) {
        if (!active || !visible) return;
        value = Math.clamp((mouseX - getX() - 4) / (getWidth() - 8), 0, 1);
        applyValue();
    }

    @Override
    public void onClick(double mouseX, double mouseY) { setFromMouse(mouseX); }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) { setFromMouse(mouseX); }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active || !visible) return false;
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            boolean increase = keyCode == GLFW.GLFW_KEY_RIGHT;
            int index = Arrays.binarySearch(MARKS, number);
            if (index >= 0) {
                index += increase ? 1 : -1;
            } else {
                int upper = -index - 1;
                index = increase ? upper : upper - 1;
            }
            selectMark(index);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
