/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/** Consecutive desk shapes share a batch; callers flush before text and at the end of rendering. */
final class DrivingGuiGeometry {
    private DrivingGuiGeometry() {}

    static void fill(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        if (x0 == x1 || y0 == y1) return;
        int left = Math.min(x0, x1), right = Math.max(x0, x1);
        int top = Math.min(y0, y1), bottom = Math.max(y0, y1);
        var pose = graphics.pose().last().pose();
        var vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        vertices.addVertex(pose, left, top, 0).setColor(color);
        vertices.addVertex(pose, left, bottom, 0).setColor(color);
        vertices.addVertex(pose, right, bottom, 0).setColor(color);
        vertices.addVertex(pose, right, top, 0).setColor(color);
    }

    static void renderOutline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        fill(graphics, x, y, x + width, y + 1, color);
        fill(graphics, x, y + height - 1, x + width, y + height, color);
        fill(graphics, x, y + 1, x + 1, y + height - 1, color);
        fill(graphics, x + width - 1, y + 1, x + width, y + height - 1, color);
    }
}
