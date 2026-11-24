/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.unrealtrainmod.blueprint.BlueprintDefinition;
import com.unrealtrainmod.wiring.WiringCatalog;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;

/** Compact placed-part inventory, independent of the resource catalog on the left. */
final class BlueprintPartList extends ObjectSelectionList<BlueprintPartList.Row> {
    private final Consumer<UUID> onSelect;
    BlueprintPartList(BlueprintEditorScreen editor, int x, int y, int width, int height) {
        this(x, y, width, height, editor::select);
        int index = 1;
        for (var node : editor.section().nodes()) {
            var row = addPart(node, index++ + ": ");
            if (node.id().equals(editor.selected)) { setSelected(row); ensureVisible(row); }
        }
    }
    BlueprintPartList(int x, int y, int width, int height, Consumer<UUID> onSelect) {
        super(Minecraft.getInstance(), width, height, y, 18);
        this.onSelect = onSelect;
        setX(x);
    }
    Row addPart(BlueprintDefinition.Node node, String prefix) {
        var model = WiringCatalog.find(node.model());
        var row = new Row(node.id(), prefix + (model == null ? "? " + node.model() : model.name()), model == null);
        addEntry(row);
        return row;
    }
    @Override public int getRowWidth() { return width - 12; }
    @Override protected int getScrollbarPosition() { return getRight() - 7; }
    @Override protected void renderListBackground(GuiGraphics g) { g.fill(getX(), getY(), getRight(), getBottom(), 0xFF202D3A); }
    @Override protected void renderListSeparators(GuiGraphics g) { }
    final class Row extends ObjectSelectionList.Entry<Row> {
        private final UUID id;
        private final String label;
        private final boolean missing;
        Row(UUID id, String label, boolean missing) { this.id = id; this.label = label; this.missing = missing; }
        @Override public Component getNarration() { return Component.literal(label); }
        @Override public boolean mouseClicked(double x, double y, int button) {
            if (button != 0) return false; onSelect.accept(id); return true;
        }
        @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (!CommonInputs.selected(key)) return false;
            onSelect.accept(id);
            return true;
        }
        @Override public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                                     int mx, int my, boolean hovered, float partial) {
            var font = Minecraft.getInstance().font;
            g.drawString(font, font.plainSubstrByWidth(label, width - 4), left + 2, top + 4, missing ? 0xFFFF8888 : 0xFFD6E6F0);
        }
    }
}
