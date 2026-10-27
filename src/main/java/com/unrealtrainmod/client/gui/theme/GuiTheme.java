/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.theme;

import net.minecraft.client.gui.GuiGraphics;

public final class GuiTheme {
    public static final int PADDING = 10;
    public static final int BACKGROUND = 0xF21B232D;
    public static final int PANEL = 0xFF111820;
    public static final int BORDER = 0xFF465668;
    public static final int ACCENT = 0xFF70C7CC;
    public static final int TEXT = 0xFFE5EDF3;
    public static final int MUTED = 0xFF9DAFBD;
    public static final int ERROR = 0xFFFF8A80;

    private GuiTheme() {}

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + height, color);
        graphics.renderOutline(x, y, width, height, BORDER);
    }
}
