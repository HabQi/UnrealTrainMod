/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import com.unrealtrainmod.client.TrainDrivingClient;
import com.unrealtrainmod.compat.ir.IrControlDefinition;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.network.payload.TrainIrControlInputPayload.Operation;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import static com.unrealtrainmod.client.gui.DrivingGuiGeometry.fill;
import static com.unrealtrainmod.client.gui.TrainDrivingOverlay.*;

/** Registry-backed floating controls. Only visible rows read live entity values. */
public final class TrainIrControlPanel {
    private static final int ROW_HEIGHT = 24;
    private record Pending(float value, long sequence, int tick) {}
    private static final Map<String, Pending> pending = new HashMap<>();
    private static IrVehicleDefinition definition = IrVehicleDefinition.NONE;
    private static UUID vehicle;
    private static Language language;
    private static List<IrControlDefinition> all = List.of(), rows = List.of();
    private static EditBox search;
    private static String query = "", dragging;
    private static boolean expanded, dirty, scrollDragging;
    private static double scrollGrab;
    private static int offset, ticks;
    private static float target, sent;

    private TrainIrControlPanel() {}

    static Component text(String key, Object... args) {
        return Component.translatable("gui.unrealtrainmod.driving.ir." + key, args);
    }

    private static void refresh() {
        var entity = TrainDrivingClient.riddenVehicle();
        var stock = entity == null ? null : VehicleCatalog.find(entity.getModelId());
        var next = stock == null ? IrVehicleDefinition.NONE : stock.ir();
        var id = entity == null ? null : entity.getUUID();
        if (next != definition || !java.util.Objects.equals(id, vehicle)) {
            reset();
            definition = next;
            vehicle = id;
            all = definition.controls().values().stream().filter(control -> !control.readOnly())
                    .sorted(Comparator.comparing(IrControlDefinition::group)).toList();
            dirty = true;
        }
        if (dirty || language != Language.getInstance()) {
            language = Language.getInstance();
            String filter = query.toLowerCase(Locale.ROOT);
            rows = all.stream().filter(control -> (control.label() + " " + control.group())
                    .toLowerCase(Locale.ROOT).contains(filter)).toList();
            dirty = false;
        }
    }

    public static boolean available() { refresh(); return !all.isEmpty(); }
    public static boolean expanded() { return expanded; }

    public static void toggle() {
        if (!available()) return;
        finishDrag();
        expanded = !expanded;
        if (!expanded && Minecraft.getInstance().screen instanceof TrainIrControlScreen screen) screen.closeInput();
    }

    private static Box bounds() { return TrainDrivingOverlay.controlPanelBounds(); }
    private static Box list() {
        var box = bounds();
        return new Box(box.x() + 4, box.y() + 34, box.width() - 8, Math.max(0, box.height() - 40));
    }
    private static int visibleRows() { return list().height() / ROW_HEIGHT; }
    private static Box scrollbar() {
        var box = list();
        int count = visibleRows();
        if (count == 0 || rows.size() <= count) return null;
        int height = Math.max(6, box.height() * count / rows.size());
        int y = box.y() + (box.height() - height) * offset / (rows.size() - count);
        return new Box(box.x() + box.width() - 3, y, 2, height);
    }
    private static Box row(int index) {
        var box = list();
        return new Box(box.x(), box.y() + index * ROW_HEIGHT, box.width() - 5, ROW_HEIGHT - 2);
    }
    private static Box action(Box row) {
        return new Box(row.x() + 3, row.y() + 12, row.width() - 6, 10);
    }

    static EditBox searchField() {
        if (search == null) {
            search = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, text("search"));
            search.setMaxLength(128);
            search.setHint(text("search_short"));
            search.setValue(query);
            search.setResponder(value -> {
                finishDrag();
                query = value; offset = 0; dirty = true;
            });
        }
        var box = bounds();
        search.setX(box.x() + 4); search.setY(box.y() + 16); search.setWidth(box.width() - 8);
        return search;
    }

    static boolean searching() { return search != null && search.isFocused(); }
    static void stopSearching() { if (search != null) search.setFocused(false); }
    static boolean contains(double x, double y) { return expanded && bounds().contains(x, y); }

    private static float actual(String group) {
        var entity = TrainDrivingClient.riddenVehicle();
        var snapshot = entity.irSnapshot();
        return definition.value(group, snapshot.values(), entity.runtimeState(), snapshot.frontCoupled(), snapshot.rearCoupled());
    }
    private static float value(String group) {
        if (group.equals(dragging)) return target;
        var requested = pending.get(group);
        return requested == null ? actual(group) : requested.value();
    }

    private static void request(String group, float value, boolean pulse) {
        request(group, value, pulse, Operation.CLICK);
    }

    private static void request(String group, float value, boolean pulse, Operation operation) {
        long sequence = TrainDrivingClient.sendIr(group, value, pulse, operation);
        if (!pulse && sequence >= 0) pending.put(group, new Pending(value, sequence, ticks));
    }

    public static void tick() {
        refresh(); ticks++;
        if (vehicle == null) return;
        long acknowledged = TrainDrivingClient.state().sequence();
        pending.entrySet().removeIf(entry -> ticks - entry.getValue().tick() > 40
                || (acknowledged >= entry.getValue().sequence() && actual(entry.getKey()) == entry.getValue().value()));
        if (dragging != null && target != sent) {
            request(dragging, target, false, Operation.UPDATE);
            sent = target;
        }
    }

    static void render(GuiGraphics graphics, double mouseX, double mouseY) {
        refresh();
        if (!expanded) return;
        var font = Minecraft.getInstance().font;
        var box = bounds();
        panel(graphics, box, BACKGROUND);
        label(graphics, font, text("title"), box.x() + 5, box.y() + 4, box.width() - 10, TEXT);
        searchField().render(graphics, (int)mouseX, (int)mouseY, 0);
        var list = list();
        int count = visibleRows();
        offset = Math.clamp(offset, 0, Math.max(0, rows.size() - count));
        graphics.enableScissor(list.x(), list.y(), list.x() + list.width(), list.y() + list.height());
        IrControlDefinition hovered = null;
        for (int i = 0; i < count && offset + i < rows.size(); i++) {
            var control = rows.get(offset + i);
            var row = row(i);
            var action = action(row);
            float value = value(control.group());
            fill(graphics, row.x(), row.y(), row.x() + row.width(), row.y() + row.height(),
                    row.contains(mouseX, mouseY) ? 0xFF172733 : PANEL);
            int labelWidth = row.width() - 32;
            label(graphics, font, Component.literal(control.label()), row.x() + 3, row.y() + 2, labelWidth, TEXT);
            var reading = Component.literal(String.format(Locale.ROOT, "%.2f", value));
            label(graphics, font, reading, row.x() + row.width() - 27, row.y() + 2, 25, CYAN);
            switch (control.type()) {
                case TOGGLE -> button(graphics, font, action, text(value >= 0.5f ? "on" : "off"), true,
                        value >= 0.5f ? GREEN : MUTED, mouseX, mouseY);
                case PRESS -> button(graphics, font, action, text("pulse"), true, CYAN, mouseX, mouseY);
                case CONTINUOUS -> {
                    panel(graphics, action, 0xFF1A2834);
                    int x = action.x() + Math.round(value * (action.width() - 6));
                    fill(graphics, action.x() + 3, action.y() + 4, action.x() + action.width() - 3, action.y() + 6, EDGE);
                    fill(graphics, x + 1, action.y() + 1, x + 5, action.y() + action.height() - 1, CYAN);
                }
            }
            if (row.contains(mouseX, mouseY)) hovered = control;
        }
        if (rows.isEmpty()) label(graphics, font, text("empty"), list.x() + 4, list.y() + 8, list.width() - 8, MUTED);
        var thumb = scrollbar();
        if (thumb != null) {
            fill(graphics, thumb.x(), thumb.y(), thumb.x() + thumb.width(), thumb.y() + thumb.height(), CYAN);
        }
        graphics.flush();
        graphics.disableScissor();
        int tipWidth = Math.min(240, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 16);
        if (hovered != null) {
            var tip = Component.literal(hovered.label()).append("\n" + hovered.group()).append("\n")
                    .append(text("details", text("type." + hovered.type().name().toLowerCase(Locale.ROOT)),
                            String.format(Locale.ROOT, "%.2f", value(hovered.group())),
                            String.format(Locale.ROOT, "%.2f", hovered.defaultValue())));
            graphics.flush();
            graphics.renderTooltip(font, font.split(tip, tipWidth), (int)mouseX, (int)mouseY);
        } else if (box.contains(mouseX, mouseY) && mouseY < box.y() + 16) {
            graphics.flush();
            graphics.renderTooltip(font, font.split(text("visual_only"), tipWidth), (int)mouseX, (int)mouseY);
        }
    }

    static boolean click(double x, double y) {
        if (!contains(x, y)) return false;
        var field = searchField();
        if (field.isMouseOver(x, y)) return true;
        var list = list();
        var thumb = scrollbar();
        if (thumb != null && new Box(list.x() + list.width() - 5, list.y(), 5, list.height()).contains(x, y)) {
            finishDrag();
            scrollDragging = true;
            scrollGrab = y >= thumb.y() && y < thumb.y() + thumb.height() ? y - thumb.y() : thumb.height() / 2.0;
            drag(x, y);
            return true;
        }
        for (int i = 0; i < visibleRows() && offset + i < rows.size(); i++) {
            var control = rows.get(offset + i);
            var action = action(row(i));
            if (!action.contains(x, y)) continue;
            switch (control.type()) {
                case TOGGLE -> request(control.group(), value(control.group()) >= 0.5f ? 0 : 1, false);
                case PRESS -> request(control.group(), 1, true);
                case CONTINUOUS -> {
                    finishDrag();
                    float current = value(control.group());
                    dragging = control.group();
                    target = sent = current;
                    request(dragging, current, false, Operation.BEGIN);
                    drag(x, y);
                }
            }
            break;
        }
        return true;
    }

    static void drag(double x, double y) {
        if (scrollDragging) {
            var list = list();
            var thumb = scrollbar();
            if (thumb == null) {
                scrollDragging = false;
                return;
            }
            int maximum = rows.size() - visibleRows();
            offset = (int)Math.clamp(Math.round((y - list.y() - scrollGrab) * maximum
                    / (list.height() - thumb.height())), 0L, (long)maximum);
            return;
        }
        if (dragging == null) return;
        for (int i = 0; i < visibleRows() && offset + i < rows.size(); i++) if (rows.get(offset + i).group().equals(dragging)) {
            var action = action(row(i));
            target = (float)Math.clamp((x - action.x() - 3) / (action.width() - 6), 0, 1);
            break;
        }
    }

    static void finishDrag() {
        scrollDragging = false;
        if (dragging == null) return;
        request(dragging, target, false, Operation.END);
        dragging = null;
    }

    static boolean scroll(double x, double y, double delta) {
        if (!contains(x, y)) return false;
        finishDrag();
        offset = Math.clamp(offset - (int)Math.signum(delta), 0, Math.max(0, rows.size() - visibleRows()));
        return true;
    }

    public static void rejectPending() { dragging = null; scrollDragging = false; pending.clear(); }

    public static void reset() {
        finishDrag();
        dragging = null; scrollDragging = false; pending.clear(); expanded = false;
        definition = IrVehicleDefinition.NONE; vehicle = null;
        all = rows = List.of(); search = null; query = ""; language = null;
        offset = ticks = 0; dirty = false;
        if (Minecraft.getInstance().screen instanceof TrainIrControlScreen screen) screen.closeInput();
    }
}
