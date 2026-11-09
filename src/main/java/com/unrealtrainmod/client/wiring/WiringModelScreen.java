/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.ClientButtonTextureManager;
import com.unrealtrainmod.client.gui.base.BaseTrainScreen;
import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.client.gui.widget.TrainIconButton;
import com.unrealtrainmod.client.gui.widget.TrainListWidget;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.wiring.WiringCatalog;
import com.unrealtrainmod.wiring.WiringDefinition;
import com.unrealtrainmod.wiring.WiringPayloads;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class WiringModelScreen extends BaseTrainScreen implements WiringClient.ResponseScreen {
    private final boolean wire;
    private final int slot;
    private final ResourceKey<Level> dimension;
    private final List<WiringDefinition> models;
    private TrainListWidget<WiringDefinition> list;
    private WiringPreviewWidget preview;
    private EditBox search;
    private TrainIconButton apply;
    private TrainIconButton reset;
    private TrainIconButton close;
    private boolean expanded;
    private WiringDefinition selected;
    private UUID pending;
    private Component status;
    public WiringModelScreen(boolean wire, int slot) {
        super(Component.translatable(wire ? "wiring.unrealtrainmod.wire_models" : "wiring.unrealtrainmod.connector_models"));
        this.wire = wire; this.slot = slot;
        models = WiringCatalog.all(wire);
        var mc = net.minecraft.client.Minecraft.getInstance(); dimension = mc.level.dimension();
        selected = WiringCatalog.find(mc.player.getMainHandItem().get(wire ? ModDataComponents.SELECTED_WIRE_MODEL.get()
                : ModDataComponents.SELECTED_CONNECTOR_MODEL.get()));
        if (selected == null && !models.isEmpty()) selected = models.getFirst();
    }
    @Override protected void buildWidgets() {
        int gap = GuiTheme.PADDING;
        int listWidth = Math.min(200, Math.max(90, (panelWidth - gap * 3) * 2 / 5))
                + TrainListWidget.SCROLLBAR_GUTTER;
        if (list == null) {
            list = new TrainListWidget<>(minecraft, listWidth, 100, models, d -> d.config().archive(),
                    d -> Component.literal(d.name()), d -> d.tags().isBlank() ? d.id().toString() : d.tags() + " · " + d.id(),
                    d -> ClientButtonTextureManager.INSTANCE.texture(d.buttonTexture()), this::select,
                    Component.translatable("wiring.unrealtrainmod.empty"), false);
            list.select(selected); list.filter(""); preview = new WiringPreviewWidget();
            preview.model(selected == null ? null : selected.id());
            search = new EditBox(font, 0, 0, listWidth, 18, Component.translatable("gui.unrealtrainmod.models.search"));
            search.setHint(Component.translatable("gui.unrealtrainmod.models.search"));
            search.setMaxLength(256); search.setResponder(query -> { list.filter(query); update(); });
            reset = new TrainIconButton(0, 0, 24, Component.translatable("gui.unrealtrainmod.preview.reset"),
                    TrainIconButton.Icon.RESET, preview::resetView);
            apply = new TrainIconButton(0, 0, 120, Component.translatable("wiring.unrealtrainmod.apply"),
                    TrainIconButton.Icon.CONFIRM, this::submit);
            close = new TrainIconButton(0, 0, 24, Component.translatable("gui.unrealtrainmod.close"),
                    TrainIconButton.Icon.CLOSE, this::onClose);
        }
        expanded = panelHeight >= 250;
        int rightX = left + gap * 2 + listWidth;
        int rightWidth = panelWidth - listWidth - gap * 3;
        search.setRectangle(listWidth, 18, left + gap, top + 32);
        list.setRectangle(listWidth, Math.max(35, panelHeight - 112), left + gap, top + 56);
        preview.setRectangle(rightWidth, Math.max(30, panelHeight - (expanded ? 144 : 112)), rightX, top + 32);
        reset.setRectangle(24, 20, preview.getRight() - 28, preview.getY() + 4);
        apply.setRectangle(120, 20, left + panelWidth - gap - 120, top + panelHeight - 30);
        close.setRectangle(24, 20, left + panelWidth - gap - 24, top + 5);
        addRenderableWidget(search); addRenderableWidget(list);
        addWidget(reset); addRenderableWidget(preview); addRenderableOnly(reset);
        addRenderableWidget(apply); addRenderableWidget(close);
        update();
    }
    private void select(WiringDefinition value) { selected = value; preview.model(value.id()); status = null; update(); }
    private void update() {
        apply.active = pending == null && selected != null && list.getSelected() != null;
        search.setEditable(pending == null); list.active = pending == null;
        preview.active = selected != null; reset.active = selected != null;
    }
    private void submit() {
        if (!apply.active) return;
        status = Component.translatable("gui.unrealtrainmod.models.pending");
        pending = UUID.randomUUID(); var tag = new CompoundTag(); tag.putInt("Slot", slot); tag.putString("Model", selected.id().toString());
        WiringClient.command(pending, wire ? WiringPayloads.Action.SELECT_WIRE : WiringPayloads.Action.SELECT_CONNECTOR, tag); update();
    }
    @Override public void accept(WiringPayloads.Message result) {
        if (!result.requestId().equals(pending)) return;
        pending = null; if (result.success()) onClose(); else { status = Component.translatable("wiring.unrealtrainmod." + result.key()); update(); }
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || !minecraft.level.dimension().equals(dimension)
                || !minecraft.player.isAlive() || !minecraft.player.mayBuild() || minecraft.player.getInventory().selected != slot
                || !minecraft.player.getMainHandItem().is(wire ? UnrealTrainMod.WIRE.get() : UnrealTrainMod.INSULATOR.get())) { onClose(); return; }
        update();
    }
    @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (list == null) return;
        int x = preview.getX(), w = preview.getWidth(), y = preview.getBottom() + 6;
        if (selected != null) {
            graphics.drawString(font, font.plainSubstrByWidth(selected.name(), w), x, y, GuiTheme.TEXT, false);
            if (expanded) {
                y += 14;
                for (String text : new String[]{selected.config().archive().getFileName().toString(), selected.tags(), selected.id().toString()}) {
                    graphics.drawString(font, font.plainSubstrByWidth(text, w), x, y, GuiTheme.MUTED, false);
                    y += 14;
                }
            }
        }
        var message = status == null ? Component.translatable("wiring.unrealtrainmod.model_count", models.size()) : status;
        graphics.drawString(font, font.plainSubstrByWidth(message.getString(), panelWidth - 20),
                left + 10, top + panelHeight - 46, GuiTheme.MUTED, false);
    }
    @Override public void removed() { if (preview != null) preview.close(); super.removed(); }
}
