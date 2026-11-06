/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import com.mojang.math.Axis;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import com.unrealtrainmod.network.payload.TrainRouteProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
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
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF8096F5;
    private static final int GREEN = 0xFF38D65B;
    private static final int YELLOW = 0xFFF3E64D;
    private static final int RED = 0xFFE33A2F;
    private static final int MIN_SPEED_SCALE = 140, SPEED_TICK_STEP = 20;
    private static final ArrayDeque<Sample> HISTORY = new ArrayDeque<>();
    private static UUID monitorEpoch;
    private static int monitorDirection;
    private static double currentDistance;
    private static double speedScale = MIN_SPEED_SCALE;
    private static TrainRouteProfile displayedProfile;
    private static List<DisplaySection> displaySections = List.of();

    private record Sample(double distance, double speed, boolean connected) {}
    private record DisplaySection(double startOffset, double endOffset, double grade, double radius) {}

    private TrainMonitorOverlay() {}

    private static Component text(String key) {
        return Component.translatable("gui.unrealtrainmod.monitor." + key);
    }

    public static void accept(TrainControlSyncPayload state, boolean interrupted) {
        if (!state.monitorEpoch().equals(monitorEpoch) || monitorDirection != state.monitorDirection()) {
            clear();
            monitorEpoch = state.monitorEpoch();
            monitorDirection = state.monitorDirection();
        }
        currentDistance = state.monitorDistance();
        boolean connected = !interrupted && !HISTORY.isEmpty();
        if (!HISTORY.isEmpty() && Math.floor(HISTORY.getLast().distance) == Math.floor(currentDistance)) {
            connected = HISTORY.removeLast().connected && connected;
        }
        HISTORY.addLast(new Sample(currentDistance, Math.abs(state.speed()) * 3.6, connected));
        while (HISTORY.size() > 1) {
            var iterator = HISTORY.iterator();
            iterator.next();
            if (iterator.next().distance >= currentDistance - TrainRouteProfile.BEHIND) break;
            HISTORY.removeFirst();
        }
        double maximum = MIN_SPEED_SCALE;
        for (var sample : HISTORY) maximum = Math.max(maximum, sample.speed);
        speedScale = Math.ceil(maximum / SPEED_TICK_STEP) * SPEED_TICK_STEP;
    }

    public static void clear() {
        HISTORY.clear();
        monitorEpoch = null;
        monitorDirection = 0;
        currentDistance = 0;
        speedScale = MIN_SPEED_SCALE;
        displayedProfile = null;
        displaySections = List.of();
    }

    /** Groups adjacent short sections while retaining the original distance endpoints. */
    private static List<DisplaySection> mergeSections(TrainRouteProfile profile) {
        var merged = new ArrayList<DisplaySection>();
        for (var section : profile.sections()) {
            var next = new DisplaySection(section.startOffset(), section.endOffset(), section.grade(), section.radius());
            if (!merged.isEmpty()) {
                var previous = merged.getLast();
                boolean straight = previous.radius() == 0 && next.radius() == 0;
                boolean sameCurve = previous.radius() != 0 && next.radius() != 0
                        && Math.copySign(1, previous.radius()) == Math.copySign(1, next.radius());
                boolean adjacent = Math.abs(next.startOffset() - previous.endOffset()) <= 1e-6;
                boolean shortEnough = next.endOffset() - previous.startOffset() < 130;
                if (adjacent && (straight || sameCurve) && shortEnough) {
                    double firstLength = previous.endOffset() - previous.startOffset();
                    double nextLength = next.endOffset() - next.startOffset();
                    double total = firstLength + nextLength;
                    double grade = (previous.grade() * firstLength + next.grade() * nextLength) / total;
                    double radius = 0;
                    if (previous.radius() != 0) {
                        double curvature = (firstLength / previous.radius() + nextLength / next.radius()) / total;
                        radius = 1 / curvature;
                    }
                    merged.set(merged.size() - 1, new DisplaySection(previous.startOffset(), next.endOffset(),
                            grade, radius));
                    continue;
                }
            }
            merged.add(next);
        }
        return List.copyOf(merged);
    }

    /** Preserves the reference layout; strokes use framebuffer pixels instead of GUI units. */
    public static void render(GuiGraphics graphics, TrainControlSyncPayload state,
                              int x, int y, int width, int height, float scale, boolean minimized, boolean hovered) {
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(Math.round(x * guiScale) / guiScale, Math.round(y * guiScale) / guiScale, 0);
            graphics.pose().scale(1F / guiScale, 1F / guiScale, 1);
            new Renderer(graphics, scale * guiScale, 1F / (float) Math.sqrt(scale), minimized)
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
        private final int tickLabelRise;
        private int fontScale;

        private Renderer(GuiGraphics graphics, float pixelsPerUnit, float fontGrowth, boolean minimized) {
            this.graphics = graphics;
            this.pixelsPerUnit = pixelsPerUnit;
            this.fontGrowth = fontGrowth;
            this.tickLabelRise = minimized ? 3 : 1;
        }

        private int pixel(double value) {
            return (int) Math.round(value * pixelsPerUnit);
        }

        private void drawMonitor(TrainControlSyncPayload state, int width, int height, boolean hovered) {
            panel(0, 0, width, height);
            int railWidth = Math.max(22, Math.min(28, width / 9));
            int graphRight = width - railWidth - 4;
            int lowerHeight = Math.min(36, Math.max(30, height / 5));
            int graphBottom = height - lowerHeight - 2;
            int lowerTop = graphBottom + 2, lowerBottom = height - 3;
            int lowerSplit = lowerTop + (lowerBottom - lowerTop) / 2;
            // Labels use the track band's pixel font size; the speed readout uses twice that size.
            int preferredScale = Math.max(1, Math.round(0.7F * pixelsPerUnit * fontGrowth));
            int trackWidth = pixel(graphRight) - pixel(6);
            int trackHeight = pixel(lowerBottom) - pixel(lowerSplit + 2);
            int fittingScale = Math.min(trackWidth / font.width(text("track")), trackHeight / font.lineHeight);
            fontScale = Math.max(1, Math.min(preferredScale, fittingScale));
            int sideLabelWidth = Math.max(font.width(text("section")), font.width(text("curve"))) * fontScale;
            int graphLeft = Math.max(24, 3 + (int) Math.ceil((sideLabelWidth + 4) / pixelsPerUnit));
            int headerRows = text("speed_short").getString().split("\n").length;
            int headerMinimum = 5 + (int) Math.ceil((Math.max(2, headerRows) * font.lineHeight + 2) * fontScale / pixelsPerUnit);
            int topHeight = Math.max(Math.round(height * 0.14F), headerMinimum);
            int graphTop = topHeight + 2;
            drawStatusHeader(3, 3, width - 6, topHeight - 5, state);
            drawMainGraph(graphLeft, graphTop, graphRight, graphBottom);
            drawLowerBands(graphLeft, lowerTop, graphRight, lowerBottom, lowerSplit, state);
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
                    number(Math.abs(state.speed()) * 3.6, 0), GREEN, fontScale * 2);
            cell(speedRight, y, limitRight - speedRight, height, text("limit_short").getString(), "", RED, fontScale);
            if (fields >= 3) {
                int distanceRight = first + fieldWidth * 13 / totalWeight;
                cell(limitRight, y, distanceRight - limitRight, height, text("distance_short").getString(), "", YELLOW, fontScale);
                if (fields == 4) {
                    cell(distanceRight, y, last - distanceRight, height, text("grade_short").getString(),
                            number(state.grade() * 1000, 1), YELLOW, fontScale);
                }
            }
            panel(last, y, x + width - last, height);
        }

        private void cell(int x, int y, int width, int height, String label, String value, int valueColor, int valueScale) {
            panel(x, y, width, height);
            String[] lines = label.split("\n");
            int labelWidth = 0;
            for (String line : lines) labelWidth = Math.max(labelWidth, font.width(line));
            int left = pixel(x), right = pixel(x + width);
            int top = pixel(y), bottom = pixel(y + height);
            int divider = left + (labelWidth + 4) * fontScale + 1;
            graphics.fill(divider, top, divider + 1, bottom, FRAME);
            int labelY = top + (bottom - top - lines.length * font.lineHeight * fontScale) / 2;
            for (String line : lines) {
                drawCenteredTextPixels(line, left + 1, labelY, divider, labelY + font.lineHeight * fontScale, BLUE, fontScale);
                labelY += font.lineHeight * fontScale;
            }
            drawCenteredTextPixels(value, divider + 1, top + 1, right - 1, bottom - 1, valueColor, valueScale);
        }

        private void drawMainGraph(int left, int top, int right, int bottom) {
            int height = bottom - top;
            int divisions = (int) (speedScale / SPEED_TICK_STEP);
            int labelRight = pixel(left) - 3 * fontScale;
            for (int i = 0; i <= divisions; i++) {
                int lineY = top + height * i / divisions;
                horizontal(left, lineY, right, GRID);
                if (i > 0) {
                    String label = Integer.toString((divisions - i) * SPEED_TICK_STEP);
                    int labelWidth = (font.width(label) - 1) * fontScale;
                    drawTextPixels(label, labelRight - labelWidth,
                            pixel(lineY - 4) - tickLabelRise * fontScale, TEXT);
                }
            }
            vertical(left, top, bottom, FRAME);
            horizontal(left, bottom, right, FRAME);
            drawDistanceGrid(left, top, right, bottom);
            int currentX = currentX(left, right);
            Sample previous = null;
            for (var sample : HISTORY) {
                double py = bottom - Math.clamp(sample.speed / speedScale, 0, 1) * height;
                if (previous != null && sample.connected) {
                    double previousY = bottom - Math.clamp(previous.speed / speedScale, 0, 1) * height;
                    distanceLine(previous.distance - currentDistance, previousY,
                            sample.distance - currentDistance, py, left, right, GREEN);
                }
                previous = sample;
            }
            vertical(currentX, top, bottom, YELLOW);
        }

        private void drawDistanceGrid(int left, int top, int right, int bottom) {
            int firstTick = (int) Math.ceil(-TrainRouteProfile.BEHIND / 100) * 100;
            int lastTick = (int) Math.floor(TrainRouteProfile.AHEAD / 100) * 100;
            for (int offset = firstTick; offset <= lastTick; offset += 100) {
                int x = distanceX(offset, left, right);
                vertical(x, top, bottom, GRID);
            }
        }

        private void drawLowerBands(int left, int top, int right, int bottom, int split, TrainControlSyncPayload state) {
            panel(left, top, right - left, bottom - top);
            horizontal(left, split, right - 1, BLUE);
            var profile = state.routeProfile();
            if (displayedProfile != profile) {
                displaySections = mergeSections(profile);
                displayedProfile = profile;
            }
            drawRouteProfile(left, top + 1, right, split - 1, split + 1, bottom - 2, displaySections);
            vertical(currentX(left, right), top, bottom - 1, YELLOW);
            drawSideLabel(text("section").getString(), 3, top, left - 3, split - top, TEXT);
            drawSideLabel(text("curve").getString(), 3, split, left - 3, bottom - split, TEXT);
        }

        private void drawRouteProfile(int left, int sectionTop, int right, int sectionBottom,
                                      int curveTop, int curveBottom, List<DisplaySection> sections) {
            if (sections.isEmpty()) return;
            int gradeMid = sectionTop + (sectionBottom - sectionTop) / 2;
            int gradeAmplitude = Math.max(1, (sectionBottom - sectionTop) / 2 - 1);
            int curveBase = curveTop + (curveBottom - curveTop) / 2;
            int curveTopLevel = curveTop + 1;
            int curveBottomLevel = curveBottom - 1;
            int previousCurveY = curveBase;
            int previousGradeY = gradeMid;
            boolean first = true;
            for (var section : sections) {
                int x0 = distanceX(Math.max(-TrainRouteProfile.BEHIND, section.startOffset()), left, right);
                int x1 = distanceX(Math.min(TrainRouteProfile.AHEAD, section.endOffset()), left, right);
                int rise = section.grade() > 0.05 ? gradeAmplitude : section.grade() < -0.05 ? -gradeAmplitude : 0;
                int curveY = curveBase;
                if (section.radius() != 0) {
                    // Signed radius is only a direction marker: left is top, right is bottom.
                    curveY = section.radius() > 0 ? curveTopLevel : curveBottomLevel;
                }
                if (!first && section.startOffset() >= -TrainRouteProfile.BEHIND) {
                    line(x0, previousGradeY, x0, gradeMid + rise, YELLOW);
                    line(x0, previousCurveY, x0, curveY, YELLOW);
                }
                distanceLine(section.startOffset(), gradeMid + rise, section.endOffset(), gradeMid - rise, left, right, YELLOW);
                distanceLine(section.startOffset(), curveY, section.endOffset(), curveY, left, right, YELLOW);
                drawProfileLabel(number(Math.abs(section.grade()), 1), x0, x1, sectionTop, sectionBottom, WHITE);
                if (section.radius() != 0) {
                    drawRadiusLabel(number(Math.abs(section.radius()), 0), x0, x1, curveTop, curveBottom);
                }
                previousGradeY = gradeMid - rise;
                previousCurveY = curveY;
                first = false;
            }
        }

        private int currentX(int left, int right) {
            return distanceX(0, left, right);
        }

        private int distanceX(double offset, int left, int right) {
            return left + (int) Math.round((offset + TrainRouteProfile.BEHIND)
                    / (TrainRouteProfile.BEHIND + TrainRouteProfile.AHEAD) * (right - left));
        }

        /** Clip in distance space before mapping or entering the framebuffer-pixel raster loop. */
        private void distanceLine(double start, double startY, double end, double endY, int left, int right, int color) {
            if (end < -TrainRouteProfile.BEHIND || start > TrainRouteProfile.AHEAD) return;
            double a = Math.max(start, -TrainRouteProfile.BEHIND), b = Math.min(end, TrainRouteProfile.AHEAD);
            double span = end - start;
            double ay = span == 0 ? startY : startY + (endY - startY) * ((a - start) / span);
            double by = span == 0 ? endY : startY + (endY - startY) * ((b - start) / span);
            line(distanceX(a, left, right), ay, distanceX(b, left, right), by, color);
        }

        private void drawRadiusLabel(String value, int left, int right, int top, int bottom) {
            int textWidth = (font.width(value) - 1) * fontScale;
            int textHeight = font.lineHeight * fontScale;
            int bandLeft = pixel(left), bandRight = pixel(right);
            int bandTop = pixel(top), bandBottom = pixel(bottom);
            if (bandRight - bandLeft < textHeight + 2 || bandBottom - bandTop < textWidth + 2) return;
            int cx = (bandLeft + bandRight) / 2, cy = (bandTop + bandBottom) / 2;
            graphics.fill(cx - textHeight / 2, cy - textWidth / 2 - 1,
                    cx + (textHeight + 1) / 2, cy + (textWidth + 1) / 2 + 1, BLACK);
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(cx - textHeight / 2, cy + textWidth / 2, 0);
                // Keep the radius value vertical, matching the reference monitor's upright track labels.
                graphics.pose().mulPose(Axis.ZP.rotationDegrees(-90));
                graphics.pose().scale(fontScale, fontScale, 1);
                graphics.drawString(font, value, 0, 0, GREEN, false);
            } finally {
                graphics.pose().popPose();
            }
        }

        private void drawProfileLabel(String value, int left, int right, int top, int bottom, int color) {
            left = pixel(left);
            right = pixel(right);
            top = pixel(top);
            bottom = pixel(bottom);
            int width = (font.width(value) - 1) * fontScale;
            if (right - left < width + 4 || bottom - top < font.lineHeight * fontScale) return;
            int x = left + (right - left - width) / 2;
            int y = top + (bottom - top - font.lineHeight * fontScale) / 2;
            graphics.fill(x - 1, y, x + width + 1, y + font.lineHeight * fontScale, BLACK);
            drawTextPixels(value, x, y, color);
        }

        private void drawSideLabel(String value, int x, int y, int width, int height, int color) {
            int left = pixel(x), top = pixel(y);
            drawTextPixels(value, left + (pixel(x + width) - left - font.width(value) * fontScale) / 2,
                    top + (pixel(y + height) - top - font.lineHeight * fontScale) / 2, color);
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

        private void drawTextPixels(String value, int x, int y, int color) {
            drawTextPixels(value, x, y, color, fontScale);
        }

        private void drawCenteredTextPixels(String value, int left, int top, int right, int bottom, int color, int scale) {
            if (value.isEmpty()) return;
            // The final character's advance includes one pixel of trailing spacing.
            int textWidth = (font.width(value) - 1) * scale;
            int x = left + Math.round((right - left - textWidth) / 2F);
            int y = top + Math.round((bottom - top - font.lineHeight * scale) / 2F);
            drawTextPixels(value, x, y, color, scale);
        }

        private void drawTextPixels(String value, int x, int y, int color, int scale) {
            if (value.isEmpty()) return;
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(x, y, 0);
                graphics.pose().scale(scale, scale, 1);
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
