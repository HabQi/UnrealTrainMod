/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.screen;

import com.unrealtrainmod.track.model.RailDefinition;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.ClientThumbnailManager;
import com.unrealtrainmod.client.gui.base.BaseTrainScreen;
import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.client.gui.widget.RailPreviewWidget;
import com.unrealtrainmod.client.gui.widget.TrainIconButton;
import com.unrealtrainmod.client.gui.widget.TrainListWidget;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.item.RailPlacerItem;
import com.unrealtrainmod.network.payload.ModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.RailModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.SelectRailModelPayload;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/** All catalog entries are selectable. The draft is applied to the held item only after server acknowledgement. */
public final class RailModelScreen extends BaseTrainScreen {
    private final int slot;
    private final ItemStack originalStack;
    private final ResourceKey<Level> dimension;
    private final PreviewCamera camera = new PreviewCamera();
    private RailDefinition selected;
    private TrainListWidget<RailDefinition> list;
    private EditBox search;
    private RailPreviewWidget preview;
    private TrainIconButton apply;
    private TrainIconButton reset;
    private TrainIconButton close;
    private boolean expanded;
    private UUID pendingRequest;
    private Component status;

    public RailModelScreen(int slot, ItemStack stack, ResourceKey<Level> dimension) {
        super(Component.translatable("gui.unrealtrainmod.rail_models.title"));
        this.slot = slot;
        this.dimension = dimension;
        originalStack = stack;
        selected = RailCatalog.find(RailPlacerItem.selectedModel(stack));
        if (selected == null && !RailCatalog.all().isEmpty()) selected = RailCatalog.all().getFirst();
    }

    @Override
    protected void buildWidgets() {
        int listWidth = Math.min(200, Math.max(90, (panelWidth - 30) * 2 / 5))
                + TrainListWidget.SCROLLBAR_GUTTER;
        if (search == null) {
            list = new TrainListWidget<>(minecraft, listWidth, 100, RailCatalog.all(),
                    model -> model.config().archive(),
                    model -> Component.literal(model.railName()),
                    model -> model.tags().isBlank() ? model.id().toString() : model.tags() + " · " + model.id(),
                    ClientThumbnailManager.INSTANCE::railImage, this::selectModel,
                    Component.translatable("gui.unrealtrainmod.rail_models.empty"));
            list.select(selected);
            list.filter("");
            search = new EditBox(font, 0, 0, listWidth, 18, Component.translatable("gui.unrealtrainmod.models.search"));
            search.setHint(Component.translatable("gui.unrealtrainmod.models.search"));
            search.setMaxLength(256);
            search.setResponder(query -> { list.filter(query); updateWidgets(); });
            preview = new RailPreviewWidget(camera, selected == null ? null : selected.id());
            reset = new TrainIconButton(0, 0, 24, Component.translatable("gui.unrealtrainmod.preview.reset"),
                    TrainIconButton.Icon.RESET, camera::reset);
            apply = new TrainIconButton(0, 0, 120, Component.translatable("gui.unrealtrainmod.rail_models.apply"),
                    TrainIconButton.Icon.CONFIRM, this::submit);
            close = new TrainIconButton(0, 0, 24, Component.translatable("gui.unrealtrainmod.close"),
                    TrainIconButton.Icon.CLOSE, this::onClose);
        }
        expanded = panelHeight >= 250;
        int rightX = left + listWidth + 20;
        int rightWidth = panelWidth - listWidth - 30;
        search.setRectangle(listWidth, 18, left + 10, top + 32);
        list.setRectangle(listWidth, Math.max(35, panelHeight - 112), left + 10, top + 56);
        list.clampScrollAmount();
        preview.setRectangle(rightWidth, Math.max(30, panelHeight - (expanded ? 144 : 112)), rightX, top + 32);
        reset.setRectangle(24, 20, preview.getRight() - 28, preview.getY() + 4);
        apply.setRectangle(120, 20, left + panelWidth - 130, top + panelHeight - 30);
        close.setRectangle(24, 20, left + panelWidth - GuiTheme.PADDING - 24, top + 5);
        addRenderableWidget(search);
        addRenderableWidget(list);
        // Handle the overlay button before the preview, but draw it above the preview.
        addWidget(reset);
        addRenderableWidget(preview);
        addRenderableOnly(reset);
        addRenderableWidget(apply);
        addRenderableWidget(close);
        updateWidgets();
    }

    private void selectModel(RailDefinition model) {
        selected = model;
        preview.setModel(model.id());
        status = null;
        updateWidgets();
    }

    private void updateWidgets() {
        apply.active = pendingRequest == null && selected != null && list.getSelected() != null;
        list.active = pendingRequest == null;
        search.setEditable(pendingRequest == null);
        preview.active = selected != null;
        reset.active = selected != null;
    }

    private void submit() {
        if (!apply.active) return;
        pendingRequest = UUID.randomUUID();
        status = Component.translatable("gui.unrealtrainmod.models.pending");
        updateWidgets();
        PacketDistributor.sendToServer(new SelectRailModelPayload(pendingRequest, slot, selected.id()));
    }

    public void acceptResult(RailModelSelectionResultPayload payload) {
        if (!payload.requestId().equals(pendingRequest)) return;
        pendingRequest = null;
        if (payload.result() == ModelSelectionResultPayload.Result.SUCCESS) { onClose(); return; }
        status = Component.translatable("gui.unrealtrainmod.rail_models." + payload.result().name().toLowerCase(Locale.ROOT));
        updateWidgets();
    }

    @Override
    public void tick() {
        var player = minecraft.player;
        if (player == null || !player.isAlive() || minecraft.level == null || !minecraft.level.dimension().equals(dimension)
                || player.getInventory().selected != slot || !player.getMainHandItem().is(UnrealTrainMod.RAIL_PLACER.get())
                || !player.hasPermissions(2) || (pendingRequest == null && player.getMainHandItem() != originalStack)) {
            onClose();
            return;
        }
        updateWidgets();
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (list == null) return;
        int x = preview.getX(), w = preview.getWidth(), y = preview.getBottom() + 6;
        if (selected != null) {
            graphics.drawString(font, font.plainSubstrByWidth(selected.railName(), w), x, y, GuiTheme.TEXT, false);
            if (expanded) {
                y += 14;
                for (String text : new String[]{selected.packName(), selected.tags(), selected.id().toString()}) {
                    graphics.drawString(font, font.plainSubstrByWidth(text, w), x, y, GuiTheme.MUTED, false);
                    y += 14;
                }
            }
        }
        Component message = status == null ? Component.translatable("gui.unrealtrainmod.rail_models.count", RailCatalog.all().size()) : status;
        graphics.drawString(font, font.plainSubstrByWidth(message.getString(), panelWidth - 20),
                left + 10, top + panelHeight - 46, GuiTheme.MUTED, false);
    }

    @Override
    public void removed() {
        if (preview != null) preview.close();
        super.removed();
    }
}
