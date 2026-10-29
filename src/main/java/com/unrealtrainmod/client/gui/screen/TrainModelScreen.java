/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.screen;

import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.gui.base.BaseTrainScreen;
import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.client.gui.widget.TrainIconButton;
import com.unrealtrainmod.client.gui.widget.TrainListWidget;
import com.unrealtrainmod.client.gui.widget.TrainPreviewWidget;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.network.payload.ModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.SelectTrainModelPayload;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TrainModelScreen extends BaseTrainScreen {
    private final InteractionHand hand;
    private final int hotbarSlot;
    private final ItemStack originalStack;
    private final Item item;
    private final boolean trackPlacement;
    private final PreviewCamera camera = new PreviewCamera();
    private VehicleDefinition selected;
    private EditBox search;
    private TrainListWidget<VehicleDefinition> list;
    private TrainPreviewWidget preview;
    private TrainIconButton apply;
    private TrainIconButton reset;
    private TrainIconButton close;
    private UUID pendingRequest;
    private Component status;
    private boolean statusError;
    private boolean expanded;
    private ResourceKey<Level> dimension;

    public TrainModelScreen(InteractionHand hand, int hotbarSlot, ItemStack stack) {
        super(Component.translatable(stack.is(UnrealTrainMod.VEHICLE.get())
                ? "gui.unrealtrainmod.models.vehicle_title" : "gui.unrealtrainmod.models.title"));
        this.hand = hand;
        this.hotbarSlot = hotbarSlot;
        originalStack = stack;
        item = stack.getItem();
        trackPlacement = stack.is(UnrealTrainMod.VEHICLE.get());
        selected = VehicleCatalog.find(stack.get(ModDataComponents.SELECTED_MODEL.get()));
        if (selected == null && !VehicleCatalog.all().isEmpty()) selected = VehicleCatalog.all().getFirst();
    }

    @Override
    protected void buildWidgets() {
        if (search == null) createWidgets();
        expanded = panelHeight >= 250;
        int gap = GuiTheme.PADDING;
        int listWidth = Math.min(200, Math.max(90, (panelWidth - gap * 3) * 2 / 5));
        int rightX = left + gap * 2 + listWidth;
        int rightWidth = panelWidth - listWidth - gap * 3;
        search.setRectangle(listWidth, 18, left + gap, top + 32);
        list.setRectangle(listWidth, Math.max(35, panelHeight - 112), left + gap, top + 56);
        list.clampScrollAmount();
        preview.setRectangle(rightWidth, Math.max(30, panelHeight - (expanded ? 144 : 112)), rightX, top + 32);
        reset.setRectangle(24, 20, preview.getRight() - 28, preview.getY() + 4);
        apply.setRectangle(120, 20, left + panelWidth - gap - 120, top + panelHeight - 30);
        close.setRectangle(24, 20, left + panelWidth - gap - 24, top + 5);
        addRenderableWidget(search);
        addRenderableWidget(list);
        // Handle the overlay button before the preview, but draw it above the preview.
        addWidget(reset);
        addRenderableWidget(preview);
        addRenderableOnly(reset);
        addRenderableWidget(apply);
        addRenderableWidget(close);
        updateApplyButton();
    }

    private void createWidgets() {
        dimension = minecraft.level.dimension();
        list = new TrainListWidget<>(minecraft, 100, 100, VehicleCatalog.all(),
                model -> model.config().archive(), model -> Component.literal(model.name()),
                model -> model.id().toString(), VehicleDefinition::buttonTexture, this::selectModel);
        list.filter("");
        list.select(selected);
        search = new EditBox(font, 0, 0, 100, 18, Component.translatable("gui.unrealtrainmod.models.search"));
        search.setMaxLength(256);
        search.setHint(Component.translatable("gui.unrealtrainmod.models.search"));
        search.setResponder(query -> { list.filter(query); updateApplyButton(); });
        preview = new TrainPreviewWidget(camera, selected == null ? null : selected.id());
        reset = new TrainIconButton(0, 0, 24, Component.translatable("gui.unrealtrainmod.preview.reset"),
                TrainIconButton.Icon.RESET, camera::reset);
        apply = new TrainIconButton(0, 0, 120, Component.translatable("gui.unrealtrainmod.models.apply"),
                TrainIconButton.Icon.CONFIRM, this::submit);
        close = new TrainIconButton(0, 0, 24, Component.translatable("gui.unrealtrainmod.close"),
                TrainIconButton.Icon.CLOSE, this::onClose);
    }

    private void selectModel(VehicleDefinition model) {
        selected = model;
        preview.setModel(model.id());
        camera.reset();
        status = null;
        statusError = false;
        updateApplyButton();
    }

    private void updateApplyButton() {
        apply.active = pendingRequest == null && selected != null && list.getSelected() != null
                && ClientModelManager.INSTANCE.model(selected.id()) != null;
        preview.active = selected != null;
        reset.active = selected != null;
    }

    private void submit() {
        pendingRequest = UUID.randomUUID();
        status = Component.translatable("gui.unrealtrainmod.models.pending");
        statusError = false;
        search.setEditable(false);
        list.active = false;
        apply.active = false;
        PacketDistributor.sendToServer(new SelectTrainModelPayload(pendingRequest, hand, hotbarSlot,
                BuiltInRegistries.ITEM.getKey(item), selected.id()));
    }

    public void acceptResult(ModelSelectionResultPayload payload) {
        if (!payload.requestId().equals(pendingRequest)) return;
        pendingRequest = null;
        if (payload.result() == ModelSelectionResultPayload.Result.SUCCESS) {
            onClose();
            return;
        }
        statusError = true;
        status = Component.translatable(switch (payload.result()) {
            case INVALID_ITEM -> "gui.unrealtrainmod.models.invalid_item";
            case UNKNOWN_MODEL -> "gui.unrealtrainmod.models.unknown_model";
            case NO_PERMISSION -> "gui.unrealtrainmod.models.no_permission";
            case SUCCESS -> throw new IllegalStateException("Success was handled above");
        });
        search.setEditable(true);
        list.active = true;
        updateApplyButton();
    }

    @Override
    public void tick() {
        var player = minecraft.player;
        if (player == null || !player.isAlive() || minecraft.level == null || !minecraft.level.dimension().equals(dimension)
                || (hand == InteractionHand.MAIN_HAND && player.getInventory().selected != hotbarSlot)
                || !player.getItemInHand(hand).is(item)
                || (pendingRequest == null && player.getItemInHand(hand) != originalStack)) {
            onClose();
            return;
        }
        updateApplyButton();
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = preview.getX(), w = preview.getWidth(), y = preview.getBottom() + 6;
        graphics.drawString(font, font.plainSubstrByWidth(selected == null
                        ? Component.translatable("item.unrealtrainmod.vehicle_placer.none").getString() : selected.name(), w),
                x, y, GuiTheme.TEXT, false);
        if (expanded && selected != null) {
            String dimensions = Component.translatable("gui.unrealtrainmod.models.dimensions",
                    String.format(java.util.Locale.ROOT, "%.2f", selected.length()),
                    String.format(java.util.Locale.ROOT, "%.2f", selected.width()),
                    String.format(java.util.Locale.ROOT, "%.2f", selected.height())).getString();
            y += 14;
            for (String text : new String[]{selected.packName(), dimensions, selected.id().toString()}) {
                graphics.drawString(font, font.plainSubstrByWidth(text, w), x, y, GuiTheme.MUTED, false);
                y += 14;
            }
        }
        var message = status != null ? status : Component.translatable(trackPlacement
                ? "gui.unrealtrainmod.models.vehicle_count" : "gui.unrealtrainmod.models.count", VehicleCatalog.all().size());
        graphics.drawString(font, font.plainSubstrByWidth(message.getString(), panelWidth - 20),
                left + 10, top + panelHeight - 46, statusError ? GuiTheme.ERROR : GuiTheme.MUTED, false);
    }

    @Override
    public void removed() {
        if (preview != null) preview.close();
        super.removed();
    }
}
