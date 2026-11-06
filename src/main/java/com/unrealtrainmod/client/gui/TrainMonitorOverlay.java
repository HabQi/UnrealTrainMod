/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import java.util.ArrayDeque;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Compact reconstruction of the reference train running monitor. */
public final class TrainMonitorOverlay {
    private static final int BLACK = 0xFF020506;
    private static final int FRAME = 0xFF7C8582;
    private static final int GRID = 0xFF1A2822;
    private static final int TEXT = 0xFFD5D8D3;
    private static final int BLUE = 0xFF8096F5;
    private static final int GREEN = 0xFF38D65B;
    private static final int YELLOW = 0xFFF3E64D;
    private static final int RED = 0xFFE33A2F;
    private static final int WINDOW_TICKS = 1200, SAMPLE_TICKS = 4;
    private static final ArrayDeque<Sample> HISTORY = new ArrayDeque<>();
    private static int ticks;
    private static double speedScale = 120;

    private record Sample(int tick, double speed) {}

    private TrainMonitorOverlay() {}

    private static Component text(String key) {
        return Component.translatable("gui.unrealtrainmod.monitor." + key);
    }

    public static void tick(TrainControlSyncPayload state, boolean stale) {
        while (!HISTORY.isEmpty() && HISTORY.getFirst().tick < ticks - WINDOW_TICKS) HISTORY.removeFirst();
        if (!stale && ticks % SAMPLE_TICKS == 0) {
            HISTORY.addLast(new Sample(ticks, Math.abs(state.speed()) * 3.6));
            double maximum = 120;
            for (var sample : HISTORY) maximum = Math.max(maximum, sample.speed);
            speedScale = Math.max(120, Math.ceil(maximum / 20) * 20);
        }
        ticks++;
    }

    public static void clear() {
        HISTORY.clear();
        ticks = 0;
        speedScale = 120;
    }

    /** Preserves the reference layout; strokes use framebuffer pixels instead of GUI units. */
    public static void render(GuiGraphics graphics, TrainControlSyncPayload state,
                              int x, int y, int width, int height, float scale, boolean hovered) {
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(Math.round(x * guiScale) / guiScale, Math.round(y * guiScale) / guiScale, 0);
            graphics.pose().scale(1F / guiScale, 1F / guiScale, 1);
            new Renderer(graphics, scale * guiScale, 1F / (float) Math.sqrt(scale))
                    .drawMonitor(state, width, height, hovered);
        } finally {
            graphics.pose().popPose();
        }
    }

    /** Coordinates retain the expanded layout and are snapped to physical pixels when drawn. */
    private static final class Renderer {
        private final GuiGraphics graphics;
        private final Font font = Minecraft.getInstance().font;
        private final float pixelsPerUnit;
        private final float fontGrowth;
        private int fontScale;

        private Renderer(GuiGraphics graphics, float pixelsPerUnit, float fontGrowth) {
            this.graphics = graphics;
            this.pixelsPerUnit = pixelsPerUnit;
            this.fontGrowth = fontGrowth;
        }

        private int pixel(double value) {
            return (int) Math.round(value * pixelsPerUnit);
        }

        private void drawMonitor(TrainControlSyncPayload state, int width, int height, boolean hovered) {
            panel(0, 0, width, height);
            int railWidth = Math.max(22, Math.min(28, width / 9));
            int graphLeft = 24, graphRight = width - railWidth - 4;
            int lowerHeight = Math.min(36, Math.max(30, height / 5));
            int graphBottom = height - lowerHeight - 2;
            int lowerTop = graphBottom + 2, lowerBottom = height - 3;
            int lowerSplit = lowerTop + (lowerBottom - lowerTop) / 2;
            // Every label and readout uses the track band's actual pixel font size.
            int preferredScale = Math.max(1, Math.round(0.7F * pixelsPerUnit * fontGrowth));
            int trackWidth = pixel(graphRight) - pixel(6);
            int trackHeight = pixel(lowerBottom) - pixel(lowerSplit + 2);
            int fittingScale = Math.min(trackWidth / font.width(text("track")), trackHeight / font.lineHeight);
            fontScale = Math.max(1, Math.min(preferredScale, fittingScale));
            int headerRows = text("speed_short").getString().split("\n").length;
            int headerMinimum = 5 + (int) Math.ceil((headerRows * font.lineHeight + 2) * fontScale / pixelsPerUnit);
            int topHeight = Math.max(Math.round(height * 0.14F), headerMinimum);
            int graphTop = topHeight + 2;
            drawStatusHeader(3, 3, width - 6, topHeight - 5, state);
            drawMainGraph(graphLeft, graphTop, graphRight, graphBottom);
            drawLowerBands(3, lowerTop, graphRight + 3, lowerBottom, lowerSplit, state);
            drawSideRail(graphRight + 3, graphTop, width - 3, graphBottom);
            if (hovered) outline(0, 0, width, height, 0xFF62DFE0);
        }

        private void drawStatusHeader(int x, int y, int width, int height, TrainControlSyncPayload state) {
            int fields = width >= 330 ? 4 : width >= 240 ? 3 : 2;
            int first = x + width / 12, last = x + width - width / 8;
            int fieldWidth = last - first;
            int totalWeight = fields == 4 ? 17 : fields == 3 ? 13 : 8;
            int speedRight = first + fieldWidth * 4 / totalWeight;
            int limitRight = first + fieldWidth * 8 / totalWeight;
            panel(x, y, first - x, height);
            cell(first, y, speedRight - first, height, text("speed_short").getString(),
                    number(Math.abs(state.speed()) * 3.6, 0), GREEN);
            cell(speedRight, y, limitRight - speedRight, height, text("limit_short").getString(), "", RED);
            if (fields >= 3) {
                int distanceRight = first + fieldWidth * 13 / totalWeight;
                cell(limitRight, y, distanceRight - limitRight, height, text("distance_short").getString(), "", YELLOW);
                if (fields == 4) {
                    cell(distanceRight, y, last - distanceRight, height, text("grade_short").getString(),
                            number(state.grade() * 1000, 1), YELLOW);
                }
            }
            panel(last, y, x + width - last, height);
        }

        private void cell(int x, int y, int width, int height, String label, String value, int valueColor) {
            panel(x, y, width, height);
            String[] lines = label.split("\n");
            int labelWidth = 0;
            for (String line : lines) labelWidth = Math.max(labelWidth, font.width(line));
            int left = pixel(x), right = pixel(x + width);
            int top = pixel(y), bottom = pixel(y + height);
            int divider = left + (labelWidth + 2) * fontScale + 1;
            graphics.fill(divider, top, divider + 1, bottom, FRAME);
            int labelY = top + (bottom - top - lines.length * font.lineHeight * fontScale) / 2;
            for (String line : lines) {
                drawTextPixels(line, left + (divider - left - font.width(line) * fontScale) / 2, labelY, BLUE);
                labelY += font.lineHeight * fontScale;
            }
            drawTextPixels(value, divider + 1 + (right - divider - 1 - font.width(value) * fontScale) / 2,
                    top + (bottom - top - font.lineHeight * fontScale) / 2, valueColor);
        }

        private void drawMainGraph(int left, int top, int right, int bottom) {
            int height = bottom - top;
            for (int i = 0; i <= 5; i++) {
                int lineY = top + height * i / 5;
                horizontal(left, lineY, right, GRID);
                drawText(Long.toString(Math.round(speedScale * (5 - i) / 5)),
                        left - 21, lineY - 4, TEXT);
            }
            vertical(left, top, bottom, FRAME);
            horizontal(left, bottom, right, FRAME);
            int currentX = left + Math.max(2, (right - left) * 27 / 100);
            Sample previous = null;
            for (var sample : HISTORY) {
                double px = currentX - (ticks - 1 - sample.tick) * (currentX - left - 2) / (double) WINDOW_TICKS;
                double py = bottom - Math.clamp(sample.speed / speedScale, 0, 1) * height;
                if (previous != null && sample.tick - previous.tick == SAMPLE_TICKS) {
                    double previousX = currentX - (ticks - 1 - previous.tick) * (currentX - left - 2) / (double) WINDOW_TICKS;
                    double previousY = bottom - Math.clamp(previous.speed / speedScale, 0, 1) * height;
                    line(previousX, previousY, px, py, GREEN);
                }
                previous = sample;
            }
            vertical(currentX, top, bottom, YELLOW);
            drawText(text("speed_axis").getString(), left + 4, top + 3, GREEN);
            drawText(text("position").getString(), currentX - 24, bottom - 13, YELLOW);
        }

        private void drawLowerBands(int left, int top, int right, int bottom, int split, TrainControlSyncPayload state) {
            panel(left, top, right - left, bottom - top);
            horizontal(left, split, right - 1, BLUE);
            drawText(text("gradient").getString(), left + 3, top + 2, TEXT);
            drawText(text("track").getString(), left + 3, split + 2, TEXT);
            drawText(number(state.grade() * 1000, 1) + "‰", right - 32, top + 2, YELLOW);
        }

        private void drawSideRail(int left, int top, int right, int bottom) {
            for (int i = 0; i < 8; i++) {
                int rowTop = top + (bottom - top) * i / 8;
                int rowBottom = top + (bottom - top) * (i + 1) / 8 - 2;
                panel(left, rowTop, right - left, rowBottom - rowTop);
            }
        }

        private void horizontal(int left, int y, int right, int color) {
            int py = pixel(y);
            graphics.fill(pixel(left), py, pixel(right) + 1, py + 1, color);
        }

        private void vertical(int x, int top, int bottom, int color) {
            int px = pixel(x);
            graphics.fill(px, pixel(top), px + 1, pixel(bottom) + 1, color);
        }

        private void line(double ax, double ay, double bx, double by, int color) {
            int x0 = pixel(ax), y0 = pixel(ay), x1 = pixel(bx), y1 = pixel(by);
            int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
            for (int i = 0; i <= steps; i++) {
                int x = x0 + (int) Math.round((x1 - x0) * i / (double) steps);
                int y = y0 + (int) Math.round((y1 - y0) * i / (double) steps);
                graphics.fill(x, y, x + 1, y + 1, color);
            }
        }

        private void drawText(String value, int x, int y, int color) {
            drawTextPixels(value, pixel(x), pixel(y), color);
        }

        private void drawTextPixels(String value, int x, int y, int color) {
            if (value.isEmpty()) return;
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(x, y, 0);
                graphics.pose().scale(fontScale, fontScale, 1);
                graphics.drawString(font, value, 0, 0, color, false);
            } finally {
                graphics.pose().popPose();
            }
        }

        private void outline(int x, int y, int width, int height, int color) {
            graphics.renderOutline(pixel(x), pixel(y), pixel(x + width) - pixel(x), pixel(y + height) - pixel(y), color);
        }

        private void panel(int x, int y, int width, int height) {
            graphics.fill(pixel(x), pixel(y), pixel(x + width), pixel(y + height), BLACK);
            outline(x, y, width, height, FRAME);
        }
    }

    private static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%1$." + decimals + "f", value);
    }
}
