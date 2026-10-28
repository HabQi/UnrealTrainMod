/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.base;

import com.unrealtrainmod.client.gui.theme.GuiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Logical GUI coordinates only; subclasses rebuild widgets from their own retained draft state. */
public abstract class BaseTrainScreen extends Screen {
    protected int left;
    protected int top;
    protected int panelWidth;
    protected int panelHeight;

    protected BaseTrainScreen(Component title) { super(title); }

    @Override
    protected final void init() {
        panelWidth = Math.min(540, width - 12);
        panelHeight = Math.min(330, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        buildWidgets();
    }

    protected abstract void buildWidgets();

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render calls this before widgets; keep the custom background free of the vanilla blur pass.
        graphics.fill(0, 0, width, height, 0x88000000);
        GuiTheme.panel(graphics, left, top, panelWidth, panelHeight, GuiTheme.BACKGROUND);
        graphics.drawString(font, title, left + GuiTheme.PADDING, top + 10, GuiTheme.TEXT, false);
        renderContents(graphics, mouseX, mouseY, partialTick);
    }

    protected abstract void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    @Override public boolean isPauseScreen() { return false; }
}
