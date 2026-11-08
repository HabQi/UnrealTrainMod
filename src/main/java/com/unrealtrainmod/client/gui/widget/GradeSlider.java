/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.widget;

import com.unrealtrainmod.track.TrackVerticalCurveParameters;
import java.math.BigDecimal;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Preserves calculated grades until edited; manual changes stop at the grade limits. */
public final class GradeSlider extends AbstractSliderButton {
    private static final double LIMIT = TrackVerticalCurveParameters.MAX_EDIT_GRADE;
    private final DoubleConsumer onChange;
    private double number;

    public GradeSlider(int x, int y, int width, double current, DoubleConsumer onChange) {
        super(x, y, width, 18, Component.empty(), 0);
        this.onChange = onChange;
        setNumber(current);
    }

    public double number() { return number; }

    public void setNumber(double number) {
        this.number = number;
        value = Math.clamp((number + LIMIT) / (2 * LIMIT), 0, 1);
        updateMessage();
    }

    public void adjust(int step, boolean increase) {
        double next = (increase ? Math.floor(number / step) + 1 : Math.ceil(number / step) - 1) * step;
        setNumber(Math.clamp(next, -LIMIT, LIMIT));
        onChange.accept(number);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable("gui.unrealtrainmod.track_curve.grade_value",
                BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()));
    }

    @Override
    protected void applyValue() {
        setNumber(Math.round(value * (2 * LIMIT) - LIMIT));
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
            adjust(1, keyCode == GLFW.GLFW_KEY_RIGHT);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
