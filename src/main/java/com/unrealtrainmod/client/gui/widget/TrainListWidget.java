/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.widget;

import com.unrealtrainmod.client.gui.theme.GuiTheme;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;

/** Native list navigation, focus and narration, with a retained selection independent of filtering. */
public final class TrainListWidget<T> extends ObjectSelectionList<TrainListWidget.Row<T>> {
    private final List<T> items;
    private final Function<T, Component> label;
    private final Function<T, String> detail;
    private final Consumer<T> onSelect;
    private final Component emptyMessage;
    private T selectedValue;

    public TrainListWidget(Minecraft minecraft, int width, int height, List<T> items,
                           Function<T, Component> label, Function<T, String> detail, Consumer<T> onSelect) {
        this(minecraft, width, height, items, label, detail, onSelect, Component.translatable("gui.unrealtrainmod.models.empty"));
    }

    public TrainListWidget(Minecraft minecraft, int width, int height, List<T> items,
                           Function<T, Component> label, Function<T, String> detail, Consumer<T> onSelect, Component emptyMessage) {
        super(minecraft, width, height, 0, 34);
        this.emptyMessage = emptyMessage;
        this.items = List.copyOf(items);
        this.label = label;
        this.detail = detail;
        this.onSelect = onSelect;
    }

    public void filter(String query) {
        String filter = query.strip().toLowerCase(Locale.ROOT);
        clearEntries();
        for (var item : items) {
            if ((label.apply(item).getString() + " " + detail.apply(item)).toLowerCase(Locale.ROOT).contains(filter)) {
                var row = new Row<>(this, item);
                addEntry(row);
                if (Objects.equals(item, selectedValue)) super.setSelected(row);
            }
        }
        setScrollAmount(0);
    }

    public void select(T item) {
        selectedValue = item;
        for (var row : children()) if (Objects.equals(row.item, item)) {
            super.setSelected(row);
            ensureVisible(row);
            break;
        }
    }

    @Override
    public void setSelected(Row<T> row) {
        if (row != null && !active) return;
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
    }

    public static final class Row<T> extends ObjectSelectionList.Entry<Row<T>> {
        private final TrainListWidget<T> owner;
        private final T item;

        private Row(TrainListWidget<T> owner, T item) { this.owner = owner; this.item = item; }

        @Override public Component getNarration() { return owner.label.apply(item); }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) return false;
            owner.setSelected(this);
            return true;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            var font = owner.minecraft.font;
            graphics.drawString(font, font.plainSubstrByWidth(owner.label.apply(item).getString(), width - 8),
                    left + 3, top + 3, GuiTheme.TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(owner.detail.apply(item), width - 8),
                    left + 3, top + 16, GuiTheme.MUTED, false);
        }
    }
}
