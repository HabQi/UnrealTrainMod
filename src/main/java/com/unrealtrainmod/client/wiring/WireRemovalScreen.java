/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.unrealtrainmod.client.ClientButtonTextureManager;
import com.unrealtrainmod.client.gui.base.BaseTrainScreen;
import com.unrealtrainmod.client.gui.widget.TrainListWidget;
import com.unrealtrainmod.wiring.WireData;
import com.unrealtrainmod.wiring.WiringCatalog;
import com.unrealtrainmod.wiring.WiringPayloads;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class WireRemovalScreen extends BaseTrainScreen implements WiringClient.ResponseScreen {
    private final List<WireData> wires;
    private final ResourceKey<Level> dimension;
    private TrainListWidget<WireData> list;
    private WireData selected;
    private Button remove;
    private UUID pending;
    private Component status = Component.empty();
    public WireRemovalScreen(CompoundTag data) {
        super(Component.translatable("wiring.unrealtrainmod.remove_title"));
        wires = data.getList("Wires", 10).stream().map(t -> WireData.load((CompoundTag)t)).toList();
        dimension = net.minecraft.client.Minecraft.getInstance().level.dimension();
    }
    @Override protected void buildWidgets() {
        if (list == null) {
            list = new TrainListWidget<>(minecraft, panelWidth - 20, 100, wires,
                    w -> WiringCatalog.find(w.model()) == null ? Path.of("Missing") : WiringCatalog.find(w.model()).config().archive(),
                    w -> Component.literal(WiringCatalog.find(w.model()) == null ? w.model().toString() : WiringCatalog.find(w.model()).name()),
                    w -> w.model().toString(), w -> WiringCatalog.find(w.model()) == null ? null
                            : ClientButtonTextureManager.INSTANCE.texture(WiringCatalog.find(w.model()).buttonTexture()),
                    value -> { selected = value; update(); }, Component.translatable("wiring.unrealtrainmod.no_connections"), false);
            list.filter("");
            remove = Button.builder(Component.translatable("wiring.unrealtrainmod.remove"), b -> submit()).bounds(0, 0, 90, 20).build();
        }
        list.setRectangle(panelWidth - 20, Math.max(35, panelHeight - 90), left + 10, top + 30);
        remove.setRectangle(90, 20, left + panelWidth - 100, top + panelHeight - 30);
        addRenderableWidget(list); addRenderableWidget(remove);
        addRenderableWidget(Button.builder(Component.translatable("wiring.unrealtrainmod.cancel"), b -> onClose())
                .bounds(left + panelWidth - 195, top + panelHeight - 30, 90, 20).build()); update();
    }
    private void update() { if (remove != null) remove.active = selected != null && pending == null; if (list != null) list.active = pending == null; }
    private void submit() {
        var tag = new CompoundTag(); tag.putUUID("Wire", selected.id()); pending = UUID.randomUUID();
        WiringClient.command(pending, WiringPayloads.Action.REMOVE_WIRE, tag); update();
    }
    @Override public void accept(WiringPayloads.Message result) {
        if (!result.requestId().equals(pending)) return;
        pending = null; if (result.success()) onClose(); else { status = Component.translatable("wiring.unrealtrainmod." + result.key()); update(); }
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || !minecraft.level.dimension().equals(dimension)
                || !minecraft.player.isAlive() || !minecraft.player.mayBuild() || !minecraft.player.getMainHandItem().isEmpty()) onClose();
    }
    @Override protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.drawString(font, status, left + 10, top + panelHeight - 48, 0xFFFF8888, false);
    }
}
