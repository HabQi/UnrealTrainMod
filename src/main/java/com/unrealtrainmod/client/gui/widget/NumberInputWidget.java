/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.widget;

import com.unrealtrainmod.client.gui.theme.GuiTheme;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Editing may be incomplete. Enter/focus loss commits; arrows adjust the last valid value. */
public final class NumberInputWidget extends EditBox {
    private final Font font;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleConsumer onCommit;
    private final String unit;
    private double committed;
    private Component error;

    public NumberInputWidget(Font font, int x, int y, int width, Component label, String unit,
                             double min, double max, double step, double value, DoubleConsumer onCommit) {
        super(font, x, y, width, 18, label);
        this.font = font;
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit;
        this.onCommit = onCommit;
        setMaxLength(24);
        setFilter(text -> text.matches("-?\\d*(\\.\\d*)?"));
        setResponder(text -> error = null);
        setNumber(value);
    }

    public void setNumber(double value) {
        committed = value;
        setValue(format(value));
    }

    public boolean commit() {
        double number;
        try {
            number = Double.parseDouble(getValue());
        } catch (NumberFormatException exception) {
            error = Component.translatable("gui.unrealtrainmod.number.invalid");
            return false;
        }
        if (!Double.isFinite(number) || number < min || number > max) {
            error = Component.translatable("gui.unrealtrainmod.number.range", format(min), format(max));
            return false;
        }
        committed = number;
        error = null;
        onCommit.accept(number);
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        if (isFocused() && !focused) commit();
        super.setFocused(focused);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            commit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            setNumber(Math.clamp(committed + (keyCode == GLFW.GLFW_KEY_UP ? step : -step), min, max));
            onCommit.accept(committed);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, unit, getRight() + 4, getY() + 5, GuiTheme.MUTED, false);
        if (error != null) graphics.drawString(font, error, getX(), getBottom() + 3, GuiTheme.ERROR, false);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        super.updateWidgetNarration(output);
        if (error != null) output.add(NarratedElementType.HINT, error);
    }

    private static String format(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
