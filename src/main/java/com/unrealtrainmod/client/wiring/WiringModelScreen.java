/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.ClientButtonTextureManager;
import com.unrealtrainmod.client.gui.base.BaseTrainScreen;
import com.unrealtrainmod.client.gui.widget.TrainListWidget;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.wiring.WiringCatalog;
import com.unrealtrainmod.wiring.WiringDefinition;
import com.unrealtrainmod.wiring.WiringPayloads;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class WiringModelScreen extends BaseTrainScreen implements WiringClient.ResponseScreen {
    private final boolean wire;
    private final int slot;
    private final ResourceKey<Level> dimension;
    private TrainListWidget<WiringDefinition> list;
    private WiringPreviewWidget preview;
    private EditBox search;
    private Button apply;
    private WiringDefinition selected;
    private UUID pending;
    private Component status = Component.empty();
    public WiringModelScreen(boolean wire, int slot) {
        super(Component.translatable(wire ? "wiring.unrealtrainmod.wire_models" : "wiring.unrealtrainmod.connector_models"));
        this.wire = wire; this.slot = slot;
        var mc = net.minecraft.client.Minecraft.getInstance(); dimension = mc.level.dimension();
        selected = WiringCatalog.find(mc.player.getMainHandItem().get(wire ? ModDataComponents.SELECTED_WIRE_MODEL.get()
                : ModDataComponents.SELECTED_CONNECTOR_MODEL.get()));
        if (selected == null && !WiringCatalog.all(wire).isEmpty()) selected = WiringCatalog.all(wire).getFirst();
    }
    @Override protected void buildWidgets() {
        int listWidth = Math.max(100, panelWidth * 2 / 5);
        if (list == null) {
            list = new TrainListWidget<>(minecraft, listWidth, 100, WiringCatalog.all(wire), d -> d.config().archive(),
                    d -> Component.literal(d.name()), d -> d.tags() + " · " + d.id(),
                    d -> ClientButtonTextureManager.INSTANCE.texture(d.buttonTexture()), this::select,
                    Component.translatable("wiring.unrealtrainmod.empty"), false);
            list.select(selected); list.filter(""); preview = new WiringPreviewWidget();
            preview.model(selected == null ? null : selected.id());
            search = new EditBox(font, 0, 0, listWidth, 18, Component.translatable("gui.unrealtrainmod.models.search"));
            search.setMaxLength(256); search.setResponder(list::filter);
            apply = Button.builder(Component.translatable("wiring.unrealtrainmod.apply"), b -> submit()).bounds(0, 0, 100, 20).build();
        }
        search.setRectangle(listWidth, 18, left + 10, top + 30);
        list.setRectangle(listWidth, Math.max(35, panelHeight - 100), left + 10, top + 52);
        preview.setRectangle(Math.max(40, panelWidth - listWidth - 30), Math.max(40, panelHeight - 100), left + listWidth + 20, top + 30);
        apply.setRectangle(90, 20, left + panelWidth - 100, top + panelHeight - 30);
        addRenderableWidget(search); addRenderableWidget(list); addRenderableWidget(preview); addRenderableWidget(apply);
        addRenderableWidget(Button.builder(Component.translatable("wiring.unrealtrainmod.cancel"), b -> onClose())
                .bounds(left + panelWidth - 195, top + panelHeight - 30, 90, 20).build());
        update();
    }
    private void select(WiringDefinition value) { selected = value; preview.model(value.id()); status = Component.empty(); update(); }
    private void update() { apply.active = pending == null && selected != null && list.getSelected() != null; search.setEditable(pending == null); list.active = pending == null; }
    private void submit() {
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
        if (selected != null) graphics.drawString(font, font.plainSubstrByWidth(selected.name(), panelWidth - 20), left + 10, top + panelHeight - 66, 0xFFDDEEFF, false);
        graphics.drawString(font, status, left + 10, top + panelHeight - 48, 0xFFFF8888, false);
    }
    @Override public void removed() { if (preview != null) preview.close(); super.removed(); }
}
