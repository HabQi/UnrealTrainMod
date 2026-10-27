/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.widget;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public final class TrainSlider extends AbstractSliderButton {
    private final Component label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleConsumer onChange;

    public TrainSlider(int x, int y, int width, Component label, double min, double max, double step,
                       double current, DoubleConsumer onChange) {
        super(x, y, width, 18, label, (current - min) / (max - min));
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.onChange = onChange;
        setNumber(current);
    }

    public void setNumber(double number) {
        value = (snap(number) - min) / (max - min);
        updateMessage();
    }

    public double number() { return snap(min + value * (max - min)); }

    private double snap(double number) {
        return Math.clamp(min + Math.round((number - min) / step) * step, min, max);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable("gui.unrealtrainmod.slider.value", label, String.format(Locale.ROOT, "%.2f", number())));
    }

    @Override
    protected void applyValue() {
        setNumber(number());
        onChange.accept(number());
    }
}
