/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.unrealtrainmod.blueprint.BlueprintDefinition;
import com.unrealtrainmod.wiring.WiringCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

/** Compact placed-part inventory, independent of the resource catalog on the left. */
final class BlueprintPartList extends ObjectSelectionList<BlueprintPartList.Row> {
    private final BlueprintEditorScreen editor;
    BlueprintPartList(BlueprintEditorScreen editor, int x, int y, int width, int height) {
        super(Minecraft.getInstance(), width, height, y, 18); this.editor = editor; setX(x);
        int index = 1;
        for (var node : editor.section().nodes()) {
            var definition = WiringCatalog.find(node.model());
            var row = new Row(node, index++ + ": " + (definition == null ? "? " + node.model() : definition.name()));
            addEntry(row); if (node.id().equals(editor.selected)) { setSelected(row); ensureVisible(row); }
        }
    }
    @Override public int getRowWidth() { return width - 12; }
    @Override protected int getScrollbarPosition() { return getRight() - 7; }
    @Override protected void renderListBackground(GuiGraphics g) { g.fill(getX(), getY(), getRight(), getBottom(), 0xFF202D3A); }
    @Override protected void renderListSeparators(GuiGraphics g) { }
    final class Row extends ObjectSelectionList.Entry<Row> {
        private final BlueprintDefinition.Node node;
        private final String label;
        Row(BlueprintDefinition.Node node, String label) { this.node = node; this.label = label; }
        @Override public Component getNarration() { return Component.literal(label); }
        @Override public boolean mouseClicked(double x, double y, int button) {
            if (button != 0) return false; editor.select(node.id()); return true;
        }
        @Override public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                                     int mx, int my, boolean hovered, float partial) {
            var font = Minecraft.getInstance().font;
            g.drawString(font, font.plainSubstrByWidth(label, width - 4), left + 2, top + 4,
                    WiringCatalog.find(node.model()) == null ? 0xFFFF8888 : 0xFFD6E6F0);
        }
    }
}
