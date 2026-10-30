/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.screen;

import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.client.map.ClientTrainMap;
import com.unrealtrainmod.network.payload.TrainMapSessionPayload;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.map.TrainMapData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/** North-up world projection. Every displayed dynamic value comes from a server monitor snapshot. */
public final class TrainMapScreen extends Screen {
    private static final int MAP_RESOLUTION = 2;
    private static final int TRACK = 0xFF94A6B8, CLOSED = 0xFF384452, OCCUPIED = 0xFFF2AB55, CONFLICT = 0xFFFF6B65;
    private static final int[] TRAIN_COLORS = {0xFF71D8E1, 0xFFA8D889, 0xFFE8BB79, 0xFFCA9EEA, 0xFF8AAFF3, 0xFFE79ABD};
    private final ClientTrainMap view;
    private int mapLeft, mapTop, mapRight, mapBottom, sideLeft, sideWidth, listTop, listBottom, detailTop;
    private int listOffset, detailOffset, detailHeight;
    private UUID selectedTrain, selectedTurnout;
    private double centerX, centerZ, scale = 1;
    private double pressX, pressY;
    private boolean fitted, dragging, moved;

    public TrainMapScreen(ClientTrainMap view) { super(text("title")); this.view = view; }
    public ClientTrainMap view() { return view; }

    private static Component text(String key, Object... args) {
        return Component.translatable("gui.unrealtrainmod.train_map." + key, args);
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
    private static int color(UUID id) { return TRAIN_COLORS[Math.floorMod(id.hashCode(), TRAIN_COLORS.length)]; }

    @Override protected void init() {
        sideWidth = Math.min(240, Math.max(136, width / 3));
        sideLeft = width - sideWidth - 6;
        mapLeft = 6; mapTop = 51; mapRight = sideLeft - 6; mapBottom = height - 29;
        listTop = mapTop + 18;
        listBottom = listTop + Math.max(24, (mapBottom - listTop) / 3);
        detailTop = listBottom + 19;
        addRenderableWidget(Button.builder(text("fit"), button -> fit()).bounds(6, 26, 82, 20).build());
        addRenderableWidget(Button.builder(text("player"), button -> {
            if (minecraft.player != null) { centerX = minecraft.player.getX(); centerZ = minecraft.player.getZ(); }
        }).bounds(92, 26, 82, 20).build());
        addRenderableWidget(Button.builder(text("close"), button -> onClose()).bounds(width - 70, 26, 64, 20).build());
    }

    @Override public void tick() {
        if (minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive()
                || !minecraft.player.hasPermissions(2) || !view.dimension.equals(minecraft.level.dimension().location())) {
            onClose(); return;
        }
        if (!fitted && view.ready()) { fit(); fitted = true; }
        listOffset = Math.clamp(listOffset, 0, Math.max(0, view.trains().size() - visibleRows()));
        if (selectedTrain != null && view.trains().stream().noneMatch(train -> train.id().equals(selectedTrain))) selectedTrain = null;
        if (selectedTurnout != null && view.turnouts().stream().noneMatch(turnout -> turnout.id().equals(selectedTurnout))) selectedTurnout = null;
    }

    private int visibleRows() { return Math.max(1, (listBottom - listTop) / 22); }

    private void fit() {
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (var track : view.tracks().values()) {
            minX = Math.min(minX, track.minX()); maxX = Math.max(maxX, track.maxX());
            minZ = Math.min(minZ, track.minZ()); maxZ = Math.max(maxZ, track.maxZ());
        }
        for (var train : view.trains()) for (var car : train.cars()) if (car.position() != null) {
            minX = Math.min(minX, car.position().x()); maxX = Math.max(maxX, car.position().x());
            minZ = Math.min(minZ, car.position().z()); maxZ = Math.max(maxZ, car.position().z());
        }
        if (minX == Double.POSITIVE_INFINITY) {
            centerX = minecraft.player == null ? 0 : minecraft.player.getX();
            centerZ = minecraft.player == null ? 0 : minecraft.player.getZ(); scale = 1;
        } else {
            centerX = (minX + maxX) / 2; centerZ = (minZ + maxZ) / 2;
            scale = Math.clamp(Math.min((mapRight - mapLeft) / Math.max(32, maxX - minX),
                    (mapBottom - mapTop) / Math.max(32, maxZ - minZ)) * 0.9, 1e-7, 64);
        }
    }

    private double screenX(double x) { return (mapLeft + mapRight) / 2.0 + (x - centerX) * scale; }
    private double screenY(double z) { return (mapTop + mapBottom) / 2.0 + (z - centerZ) * scale; }
    private boolean inMap(double x, double y) { return x > mapLeft && x < mapRight && y > mapTop && y < mapBottom; }
    private boolean stale() { return view.stale(minecraft.gui.getGuiTicks()); }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, GuiTheme.BACKGROUND);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        String status = view.error() != null ? view.error() : !view.ready() ? "loading" : stale() ? "stale" : "live";
        var statusText = text(status);
        String statusLabel = font.plainSubstrByWidth(statusText.getString(), width * 2 / 3);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), width - font.width(statusLabel) - 24), 8, 8, GuiTheme.TEXT, false);
        graphics.drawString(font, statusLabel, width - font.width(statusLabel) - 8, 8,
                view.error() != null || stale() ? GuiTheme.ERROR : GuiTheme.ACCENT, false);
        GuiTheme.panel(graphics, mapLeft, mapTop, mapRight - mapLeft, mapBottom - mapTop, GuiTheme.PANEL);
        graphics.enableScissor(mapLeft + 1, mapTop + 1, mapRight, mapBottom);
        drawMap(graphics);
        graphics.disableScissor();
        graphics.drawString(font, "N ↑", mapLeft + 6, mapTop + 6, GuiTheme.TEXT, false);
        if (view.ready() && view.tracks().isEmpty()) graphics.drawString(font, text("empty"), mapLeft + 8, mapTop + 22, GuiTheme.MUTED, false);
        drawSidebar(graphics);
        graphics.drawString(font, font.plainSubstrByWidth(text("legend").getString(), width - 12), 6, height - 23, GuiTheme.MUTED, false);
        double wx = centerX + (mouseX - (mapLeft + mapRight) / 2.0) / scale;
        double wz = centerZ + (mouseY - (mapTop + mapBottom) / 2.0) / scale;
        var footer = text("footer", view.dimension.toString(), view.tracks().size(), view.trains().size(), number(wx), number(wz));
        graphics.drawString(font, font.plainSubstrByWidth(footer.getString(), width - 12), 6, height - 12, GuiTheme.MUTED, false);
        if (mouseY >= height - 13) graphics.renderTooltip(font, font.split(footer, Math.min(360, width - 16)), mouseX, mouseY);
        else if (mouseY >= height - 25) graphics.renderTooltip(font, font.split(text("legend"), Math.min(360, width - 16)), mouseX, mouseY);
        else if (mouseY < 22) graphics.renderTooltip(font, statusText, mouseX, mouseY);
    }

    private void drawMap(GuiGraphics graphics) {
        for (var entry : view.tracks().entrySet()) {
            var track = entry.getValue();
            if (screenX(track.maxX()) < mapLeft || screenX(track.minX()) > mapRight
                    || screenY(track.maxZ()) < mapTop || screenY(track.minZ()) > mapBottom) continue;
            var enabled = view.paths().get(entry.getKey());
            int tint = enabled == null ? TRACK : stale() ? GuiTheme.MUTED : enabled ? GuiTheme.ACCENT : CLOSED;
            polyline(graphics, track.points(), tint, 1);
        }
        for (var overlay : view.overlays()) {
            var value = overlay.occupancy();
            boolean selected = value.trainId().equals(selectedTrain) || (value.otherTrain() != null && value.otherTrain().equals(selectedTrain));
            int tint = stale() ? GuiTheme.MUTED : value.otherTrain() != null ? CONFLICT : selected ? 0xFFFFD08A : OCCUPIED;
            polyline(graphics, overlay.points(), tint, selected ? 3 : 2);
        }
        for (var turnout : view.turnouts()) {
            int x = (int)screenX(turnout.position().x()), y = (int)screenY(turnout.position().z());
            if (!inMap(x, y)) continue;
            int tint = turnout.id().equals(selectedTurnout) ? 0xFFFFFFFF : stale() ? GuiTheme.MUTED
                    : turnout.occupant() == null ? GuiTheme.ACCENT : OCCUPIED;
            graphics.renderOutline(x - 3, y - 3, 7, 7, tint);
        }
        for (var train : view.trains()) {
            boolean labeled = false;
            for (var car : train.cars()) {
                if (car.position() == null) continue;
                int x = (int)screenX(car.position().x()), y = (int)screenY(car.position().z());
                if (!inMap(x, y)) continue;
                int tint = stale() || !car.loaded() ? GuiTheme.MUTED : color(train.id());
                graphics.fill(x - 2, y - 2, x + 3, y + 3, tint);
                if (train.id().equals(selectedTrain)) graphics.renderOutline(x - 4, y - 4, 9, 9, 0xFFFFFFFF);
                double length = Math.hypot(car.directionX(), car.directionZ());
                if (train.running() && car.loaded() && !stale() && length > 1e-9) {
                    double dx = car.directionX() / length, dz = car.directionZ() / length;
                    double ex = x + dx * 12, ey = y + dz * 12;
                    line(graphics, x, y, ex, ey, tint, 1);
                    line(graphics, ex, ey, ex - dx * 4 + dz * 3, ey - dz * 4 - dx * 3, tint, 1);
                    line(graphics, ex, ey, ex - dx * 4 - dz * 3, ey - dz * 4 + dx * 3, tint, 1);
                }
                if (!labeled) {
                    graphics.drawString(font, shortId(train.id()), x + 6, y - 10, tint, false);
                    labeled = true;
                }
            }
        }
        if (minecraft.player != null) {
            double x = screenX(minecraft.player.getX()), y = screenY(minecraft.player.getZ());
            if (inMap(x, y)) {
                line(graphics, x - 3, y, x + 3, y, 0xFFFFFFFF, 1);
                line(graphics, x, y - 3, x, y + 3, 0xFFFFFFFF, 1);
            }
        }
    }

    private void polyline(GuiGraphics graphics, List<TrackVector> points, int tint, int thickness) {
        double x = screenX(points.getFirst().x()), y = screenY(points.getFirst().z());
        for (int i = 1; i < points.size(); i++) {
            var point = points.get(i);
            double nextX = screenX(point.x()), nextY = screenY(point.z());
            if (i != points.size() - 1 && Math.max(Math.abs(nextX - x), Math.abs(nextY - y)) < 1.0 / MAP_RESOLUTION) continue;
            line(graphics, x, y, nextX, nextY, tint, thickness);
            x = nextX; y = nextY;
        }
    }

    /** Clip before rasterizing, so distant track never creates an unbounded pixel loop. */
    private void line(GuiGraphics graphics, double x0, double y0, double x1, double y1, int tint, int thickness) {
        double dx = x1 - x0, dy = y1 - y0, lo = 0, hi = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 - mapLeft, mapRight - x0, y0 - mapTop, mapBottom - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return; }
            else {
                double t = q[i] / p[i];
                if (p[i] < 0) lo = Math.max(lo, t); else hi = Math.min(hi, t);
                if (lo > hi) return;
            }
        }
        double ax = x0 + lo * dx, ay = y0 + lo * dy;
        double bx = x0 + hi * dx, by = y0 + hi * dy;
        int steps = Math.max(1, (int)Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(by - ay)) * MAP_RESOLUTION));
        int radius = thickness / 2 * MAP_RESOLUTION;
        int strokeSize = thickness * MAP_RESOLUTION;
        graphics.pose().pushPose();
        try {
            // Finer raster positions, with stroke dimensions still measured in logical GUI pixels.
            graphics.pose().scale(1.0f / MAP_RESOLUTION, 1.0f / MAP_RESOLUTION, 1);
            for (int i = 0; i <= steps; i++) {
                int x = (int)Math.round((ax + (bx - ax) * i / steps) * MAP_RESOLUTION) - radius;
                int y = (int)Math.round((ay + (by - ay) * i / steps) * MAP_RESOLUTION) - radius;
                graphics.fill(x, y, x + strokeSize, y + strokeSize, tint);
            }
        } finally { graphics.pose().popPose(); }
    }

    private Component state(TrainMapData.Train train) {
        return Component.translatable("commands.unrealtrainmod.train.state."
                + (train.running() ? "running" : train.reason().name().toLowerCase(Locale.ROOT)));
    }

    private void drawSidebar(GuiGraphics graphics) {
        GuiTheme.panel(graphics, sideLeft, mapTop, sideWidth, mapBottom - mapTop, GuiTheme.PANEL);
        graphics.drawString(font, text("trains", view.trains().size()), sideLeft + 6, mapTop + 5, GuiTheme.TEXT, false);
        graphics.enableScissor(sideLeft + 1, listTop, width - 7, listBottom);
        for (int i = listOffset; i < Math.min(view.trains().size(), listOffset + visibleRows()); i++) {
            var train = view.trains().get(i);
            int y = listTop + (i - listOffset) * 22;
            if (train.id().equals(selectedTrain)) graphics.fill(sideLeft + 2, y, width - 8, y + 21, 0xFF30414E);
            boolean loaded = train.cars().stream().allMatch(TrainMapData.Car::loaded);
            int tint = stale() || !loaded ? GuiTheme.MUTED : color(train.id());
            graphics.drawString(font, shortId(train.id()), sideLeft + 6, y + 1, tint, false);
            var label = loaded ? state(train) : text("unloaded");
            graphics.drawString(font, font.plainSubstrByWidth(label.getString(), sideWidth - 12), sideLeft + 6, y + 11, GuiTheme.MUTED, false);
        }
        graphics.disableScissor();
        graphics.drawString(font, text("details"), sideLeft + 6, listBottom + 5, GuiTheme.TEXT, false);
        var lines = new ArrayList<FormattedCharSequence>();
        for (var component : details()) lines.addAll(font.split(component, sideWidth - 12));
        detailHeight = lines.size() * (font.lineHeight + 2);
        detailOffset = Math.clamp(detailOffset, 0, Math.max(0, detailHeight - (mapBottom - detailTop - 4)));
        graphics.enableScissor(sideLeft + 1, detailTop, width - 7, mapBottom - 2);
        for (int i = 0; i < lines.size(); i++) {
            int y = detailTop + i * (font.lineHeight + 2) - detailOffset;
            if (y + font.lineHeight >= detailTop && y < mapBottom) graphics.drawString(font, lines.get(i), sideLeft + 6, y, GuiTheme.MUTED, false);
        }
        graphics.disableScissor();
    }

    private List<Component> details() {
        var lines = new ArrayList<Component>();
        var train = view.trains().stream().filter(value -> value.id().equals(selectedTrain)).findFirst().orElse(null);
        if (train != null) {
            lines.add(text("train_id", train.id().toString()));
            lines.add(state(train));
            lines.add(text("restored", text(train.restored() ? "yes" : "no")));
            lines.add(text("speed", number(train.configuredSpeed()), number(train.actualSpeed())));
            lines.add(text("speed_note"));
            long conflicts = view.overlays().stream().filter(value -> value.occupancy().otherTrain() != null
                    && (value.occupancy().trainId().equals(train.id()) || value.occupancy().otherTrain().equals(train.id()))).count();
            lines.add(text("conflicts", conflicts));
            for (int i = 0; i < train.cars().size(); i++) {
                var car = train.cars().get(i);
                lines.add(text("member", i + 1, car.id().toString()));
                lines.add(text("model", car.model().toString()));
                lines.add(text(car.loaded() ? "loaded" : "unloaded"));
                lines.add(text("source_" + car.source().name().toLowerCase(Locale.ROOT)));
                if (car.position() != null) lines.add(text("position", number(car.position().x()), number(car.position().y()), number(car.position().z())));
            }
        } else {
            var turnout = view.turnouts().stream().filter(value -> value.id().equals(selectedTurnout)).findFirst().orElse(null);
            if (turnout == null) lines.add(text("select_hint"));
            else {
                lines.add(text("turnout_id", turnout.id().toString()));
                lines.add(text("controls", turnout.controls()));
                lines.add(text("occupant", turnout.occupant() == null ? text("none") : Component.literal(turnout.occupant().toString())));
                lines.add(text("position", number(turnout.position().x()), number(turnout.position().y()), number(turnout.position().z())));
            }
        }
        return lines;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        if (mouseX >= sideLeft && mouseY >= listTop && mouseY < listBottom) {
            int index = listOffset + (int)((mouseY - listTop) / 22);
            if (index < view.trains().size() && index < listOffset + visibleRows()) {
                var train = view.trains().get(index);
                selectedTrain = train.id(); selectedTurnout = null; detailOffset = 0;
                train.cars().stream().filter(car -> car.position() != null).findFirst().ifPresent(car -> {
                    centerX = car.position().x(); centerZ = car.position().z();
                });
            }
            return true;
        }
        if (inMap(mouseX, mouseY)) { dragging = true; moved = false; pressX = mouseX; pressY = mouseY; return true; }
        return false;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && dragging) {
            centerX -= deltaX / scale; centerZ -= deltaY / scale;
            moved |= Math.hypot(mouseX - pressX, mouseY - pressY) > 3;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            if (!moved && inMap(mouseX, mouseY)) pick(mouseX, mouseY);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void pick(double mouseX, double mouseY) {
        selectedTrain = null; selectedTurnout = null; detailOffset = 0;
        double nearest = 9;
        for (var train : view.trains()) for (var car : train.cars()) if (car.position() != null) {
            double distance = Math.hypot(screenX(car.position().x()) - mouseX, screenY(car.position().z()) - mouseY);
            if (distance < nearest) { nearest = distance; selectedTrain = train.id(); }
        }
        if (selectedTrain != null) return;
        for (var turnout : view.turnouts()) {
            double distance = Math.hypot(screenX(turnout.position().x()) - mouseX, screenY(turnout.position().z()) - mouseY);
            if (distance < nearest) { nearest = distance; selectedTurnout = turnout.id(); }
        }
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inMap(mouseX, mouseY)) {
            double anchorX = centerX + (mouseX - (mapLeft + mapRight) / 2.0) / scale;
            double anchorZ = centerZ + (mouseY - (mapTop + mapBottom) / 2.0) / scale;
            scale = Math.clamp(scale * Math.pow(1.2, scrollY), 1e-7, 64);
            centerX = anchorX - (mouseX - (mapLeft + mapRight) / 2.0) / scale;
            centerZ = anchorZ - (mouseY - (mapTop + mapBottom) / 2.0) / scale;
            return true;
        }
        if (mouseX >= sideLeft && mouseY >= listTop && mouseY < listBottom) {
            listOffset = Math.clamp(listOffset - (int)Math.signum(scrollY), 0, Math.max(0, view.trains().size() - visibleRows()));
            return true;
        }
        if (mouseX >= sideLeft && mouseY >= detailTop && mouseY < mapBottom) {
            detailOffset = Math.clamp(detailOffset - (int)(scrollY * 24), 0, Math.max(0, detailHeight - (mapBottom - detailTop - 4)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        if (minecraft.getConnection() != null) PacketDistributor.sendToServer(new TrainMapSessionPayload(
                view.session, view.dimension, TrainMapSessionPayload.Status.CLOSE));
        view.clear();
        super.removed();
    }
}
