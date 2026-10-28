/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.widget;

import com.unrealtrainmod.client.gui.theme.GuiTheme;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.network.chat.Component;

/** Package-grouped model entries, with a retained selection independent of filtering. */
public final class TrainListWidget<T> extends ObjectSelectionList<TrainListWidget.Row<T>> {
    private final List<T> items;
    private final Function<T, Path> archive;
    private final Function<T, Component> label;
    private final Function<T, String> detail;
    private final Consumer<T> onSelect;
    private final Component emptyMessage;
    private T selectedValue;

    public TrainListWidget(Minecraft minecraft, int width, int height, List<T> items,
                           Function<T, Path> archive, Function<T, Component> label,
                           Function<T, String> detail, Consumer<T> onSelect) {
        this(minecraft, width, height, items, archive, label, detail, onSelect,
                Component.translatable("gui.unrealtrainmod.models.empty"));
    }

    public TrainListWidget(Minecraft minecraft, int width, int height, List<T> items,
                           Function<T, Path> archive, Function<T, Component> label,
                           Function<T, String> detail, Consumer<T> onSelect, Component emptyMessage) {
        super(minecraft, width, height, 0, 34);
        this.emptyMessage = emptyMessage;
        this.items = List.copyOf(items);
        this.archive = archive;
        this.label = label;
        this.detail = detail;
        this.onSelect = onSelect;
    }

    public void filter(String query) {
        String filter = query.strip().toLowerCase(Locale.ROOT);
        clearEntries();
        var groups = new LinkedHashMap<Path, List<T>>();
        for (var item : items) {
            Path pack = archive.apply(item);
            String search = label.apply(item).getString() + " " + pack.getFileName() + " " + detail.apply(item);
            if (search.toLowerCase(Locale.ROOT).contains(filter)) {
                groups.computeIfAbsent(pack, key -> new ArrayList<>()).add(item);
            }
        }
        for (var group : groups.entrySet()) {
            addEntry(new Row<>(this, null, Component.literal(group.getKey().getFileName().toString())));
            for (var item : group.getValue()) {
                var row = new Row<>(this, item, null);
                addEntry(row);
                if (Objects.equals(item, selectedValue)) super.setSelected(row);
            }
        }
        setScrollAmount(0);
    }

    public void select(T item) {
        selectedValue = item;
        for (var row : children()) if (row.heading == null && Objects.equals(row.item, item)) {
            super.setSelected(row);
            ensureVisible(row);
            break;
        }
    }

    @Override
    public void setSelected(Row<T> row) {
        if (row != null && (!active || row.heading != null)) return;
        super.setSelected(row);
        if (row != null && !Objects.equals(selectedValue, row.item)) {
            selectedValue = row.item;
            onSelect.accept(row.item);
        }
    }

    @Override public int getRowWidth() { return getWidth() - 12; }
    @Override protected int getScrollbarPosition() { return getRight() - 7; }
    @Override protected boolean isValidMouseClick(int button) { return active && super.isValidMouseClick(button); }

    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        return active ? super.nextFocusPath(event) : null;
    }

    @Override
    protected Row<T> nextEntry(ScreenDirection direction) {
        return super.nextEntry(direction, row -> row.heading == null);
    }

    @Override
    protected void narrateListElementPosition(NarrationElementOutput output, Row<T> entry) {
        if (entry.heading != null) return;
        int count = 0;
        int position = 0;
        for (var row : children()) {
            if (row.heading != null) continue;
            count++;
            if (row == entry) position = count;
        }
        if (count > 1 && position > 0) {
            output.add(NarratedElementType.POSITION, Component.translatable("narrator.position.list", position, count));
        }
    }

    @Override
    protected void renderListBackground(GuiGraphics graphics) {
        GuiTheme.panel(graphics, getX(), getY(), width, height, GuiTheme.PANEL);
    }

    @Override protected void renderListSeparators(GuiGraphics graphics) {}

    @Override
    protected void renderDecorations(GuiGraphics graphics, int mouseX, int mouseY) {
        if (children().isEmpty()) {
            graphics.drawWordWrap(minecraft.font, emptyMessage,
                    getX() + 6, getY() + 8, width - 12, GuiTheme.MUTED);
        }
        var hovered = getHovered();
        if (hovered != null && hovered.heading != null) {
            minecraft.screen.setTooltipForNextRenderPass(hovered.heading);
        }
    }

    public static final class Row<T> extends ObjectSelectionList.Entry<Row<T>> {
        private final TrainListWidget<T> owner;
        private final T item;
        private final Component heading;

        private Row(TrainListWidget<T> owner, T item, Component heading) {
            this.owner = owner;
            this.item = item;
            this.heading = heading;
        }

        @Override public Component getNarration() { return heading != null ? heading : owner.label.apply(item); }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || heading != null) return false;
            owner.setSelected(this);
            return true;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            var font = owner.minecraft.font;
            if (heading != null) {
                graphics.fill(left, top + 2, left + width, top + height - 2, GuiTheme.BACKGROUND);
                graphics.drawString(font, font.plainSubstrByWidth(heading.getString(), width - 8),
                        left + 3, top + 9, GuiTheme.ACCENT, false);
                graphics.hLine(left, left + width - 1, top + height - 2, GuiTheme.BORDER);
                return;
            }
            graphics.drawString(font, font.plainSubstrByWidth(owner.label.apply(item).getString(), width - 8),
                    left + 3, top + 3, GuiTheme.TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(owner.detail.apply(item), width - 8),
                    left + 3, top + 16, GuiTheme.MUTED, false);
        }
    }
}
