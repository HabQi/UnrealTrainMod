/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.widget;

import com.unrealtrainmod.client.gui.theme.GuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

public final class TrainIconButton extends AbstractButton {
    public enum Icon { CONFIRM, RESET, CLOSE }

    private final Icon icon;
    private final Runnable action;

    public TrainIconButton(int x, int y, int width, Component label, Icon icon, Runnable action) {
        super(x, y, width, 20, label);
        this.icon = icon;
        this.action = action;
        setTooltip(Tooltip.create(label));
    }

    @Override public void onPress() { action.run(); }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int color = active ? GuiTheme.TEXT : GuiTheme.MUTED;
        GuiTheme.panel(graphics, getX(), getY(), width, height,
                active && isHoveredOrFocused() ? 0xFF354B5A : GuiTheme.PANEL);
        int x = getX() + 6, y = getY() + 6;
        switch (icon) {
            case CONFIRM -> {
                for (int i = 0; i < 4; i++) graphics.fill(x + i, y + 4 + i, x + i + 2, y + 6 + i, color);
                for (int i = 0; i < 7; i++) graphics.fill(x + 3 + i, y + 7 - i, x + 5 + i, y + 9 - i, color);
            }
            case CLOSE -> {
                for (int i = 0; i < 8; i++) {
                    graphics.fill(x + i, y + i, x + i + 1, y + i + 1, color);
                    graphics.fill(x + 7 - i, y + i, x + 8 - i, y + i + 1, color);
                }
            }
            case RESET -> {
                graphics.hLine(x, x + 8, y + 1, color);
                graphics.vLine(x + 8, y + 1, y + 7, color);
                graphics.hLine(x + 1, x + 8, y + 7, color);
                graphics.vLine(x + 1, y + 4, y + 7, color);
                graphics.hLine(x, x + 3, y + 3, color);
                graphics.vLine(x, y - 1, y + 3, color);
            }
        }
        if (width > 26) {
            renderScrollingString(graphics, Minecraft.getInstance().font, getMessage(),
                    getX() + 22, getY(), getRight() - 5, getBottom(), color);
        }
        if (isFocused()) graphics.renderOutline(getX(), getY(), width, height, GuiTheme.ACCENT);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
