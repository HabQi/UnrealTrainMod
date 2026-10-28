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
import com.unrealtrainmod.client.gui.widget.NumberInputWidget;
import com.unrealtrainmod.client.gui.widget.TrainIconButton;
import com.unrealtrainmod.client.gui.widget.TrainListWidget;
import com.unrealtrainmod.client.gui.widget.TrainPreviewWidget;
import com.unrealtrainmod.client.gui.widget.TrainSlider;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.item.ModelPlacerItem;
import com.unrealtrainmod.network.payload.ModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.SelectTrainModelPayload;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TrainModelScreen extends BaseTrainScreen {
    private final InteractionHand hand;
    private final int hotbarSlot;
    private final ItemStack originalStack;
    private final PreviewCamera camera = new PreviewCamera();
    private VehicleDefinition selected;
    private EditBox search;
    private TrainListWidget<VehicleDefinition> list;
    private TrainPreviewWidget preview;
    private NumberInputWidget yawInput;
    private TrainSlider zoomSlider;
    private TrainIconButton apply;
    private TrainIconButton reset;
    private TrainIconButton close;
    private UUID pendingRequest;
    private Component status;
    private boolean statusError;
    private boolean expanded;
    private ResourceKey<Level> dimension;

    public TrainModelScreen(InteractionHand hand, int hotbarSlot, ItemStack stack) {
        super(Component.translatable("gui.unrealtrainmod.models.title"));
        this.hand = hand;
        this.hotbarSlot = hotbarSlot;
        originalStack = stack;
        selected = VehicleCatalog.find(ModelPlacerItem.selectedModel(stack));
        if (selected == null && !VehicleCatalog.all().isEmpty()) selected = VehicleCatalog.all().getFirst();
    }

    @Override
    protected void buildWidgets() {
        if (search == null) createWidgets();
        expanded = panelWidth >= 420 && panelHeight >= 250;
        int gap = GuiTheme.PADDING;
        int listWidth = Math.min(200, (panelWidth - gap * 3) * 2 / 5);
        int rightX = left + gap * 2 + listWidth;
        int rightWidth = panelWidth - listWidth - gap * 3;
        search.setRectangle(listWidth, 18, left + gap, top + 32);
        list.setRectangle(listWidth, panelHeight - 116, left + gap, top + 56);
        list.clampScrollAmount();
        preview.setRectangle(rightWidth, panelHeight - (expanded ? 160 : 116), rightX, top + 32);
        reset.setRectangle(24, 20, preview.getRight() - 28, preview.getY() + 4);
        yawInput.setRectangle(64, 18, rightX, top + panelHeight - 82);
        yawInput.visible = expanded;
        zoomSlider.setRectangle(rightWidth - 90, 18, rightX + 90, yawInput.getY());
        zoomSlider.visible = expanded;
        apply.setRectangle(expanded ? 150 : 126, 20,
                left + panelWidth - gap - (expanded ? 150 : 126), top + panelHeight - 30);
        close.setRectangle(24, 20, left + panelWidth - gap - 24, top + 5);
        addRenderableWidget(search);
        addRenderableWidget(list);
        // Handle the overlay button before the preview, but draw it above the preview.
        addWidget(reset);
        addRenderableWidget(preview);
        addRenderableWidget(yawInput);
        addRenderableWidget(zoomSlider);
        addRenderableOnly(reset);
        addRenderableWidget(apply);
        addRenderableWidget(close);
        updateApplyButton();
    }

    private void createWidgets() {
        dimension = minecraft.level.dimension();
        list = new TrainListWidget<>(minecraft, 100, 100, VehicleCatalog.all(),
                model -> model.config().archive(), model -> Component.literal(model.name()),
                model -> model.id().toString(), this::selectModel);
        list.filter("");
        list.select(selected);
        search = new EditBox(font, 0, 0, 100, 18, Component.translatable("gui.unrealtrainmod.models.search"));
        search.setMaxLength(256);
        search.setHint(Component.translatable("gui.unrealtrainmod.models.search"));
        search.setResponder(query -> { list.filter(query); updateApplyButton(); });
        preview = new TrainPreviewWidget(camera, selected == null ? null : selected.id(), this::syncCameraInputs);
        yawInput = new NumberInputWidget(font, 0, 0, 64,
                Component.translatable("gui.unrealtrainmod.preview.yaw"), "°", -180, 180, 5, camera.yaw(), camera::setYaw);
        zoomSlider = new TrainSlider(0, 0, 120, Component.translatable("gui.unrealtrainmod.preview.zoom"),
                0.5, 2, 0.05, camera.zoom(), camera::setZoom);
        reset = new TrainIconButton(0, 0, 24, Component.translatable("gui.unrealtrainmod.preview.reset"),
                TrainIconButton.Icon.RESET, this::resetCamera);
        apply = new TrainIconButton(0, 0, 150, Component.translatable("gui.unrealtrainmod.models.apply"),
                TrainIconButton.Icon.CONFIRM, this::submit);
        close = new TrainIconButton(0, 0, 24, Component.translatable("gui.unrealtrainmod.close"),
                TrainIconButton.Icon.CLOSE, this::onClose);
    }

    private void selectModel(VehicleDefinition model) {
        selected = model;
        preview.setModel(model.id());
        resetCamera();
        status = null;
        statusError = false;
        updateApplyButton();
    }

    private void syncCameraInputs() {
        if (!yawInput.isFocused()) yawInput.setNumber(Math.round(camera.yaw() * 10) / 10.0);
        zoomSlider.setNumber(camera.zoom());
    }

    private void resetCamera() {
        camera.reset();
        yawInput.setNumber(camera.yaw());
        zoomSlider.setNumber(camera.zoom());
    }

    private void updateApplyButton() {
        apply.active = pendingRequest == null && selected != null && list.getSelected() != null
                && ClientModelManager.INSTANCE.model(selected.id()) != null;
        preview.active = selected != null;
        reset.active = selected != null;
        yawInput.active = selected != null;
        zoomSlider.active = selected != null;
    }

    private void submit() {
        if (expanded && !yawInput.commit()) return;
        pendingRequest = UUID.randomUUID();
        status = Component.translatable("gui.unrealtrainmod.models.pending");
        statusError = false;
        search.setEditable(false);
        list.active = false;
        apply.active = false;
        PacketDistributor.sendToServer(new SelectTrainModelPayload(pendingRequest, hand, hotbarSlot, selected.id()));
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
                || !player.getItemInHand(hand).is(UnrealTrainMod.VEHICLE_PLACER.get())
                || (pendingRequest == null && player.getItemInHand(hand) != originalStack)) {
            onClose();
            return;
        }
        updateApplyButton();
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawString(font, font.plainSubstrByWidth(selected == null
                        ? Component.translatable("item.unrealtrainmod.vehicle_placer.none").getString() : selected.name(), preview.getWidth()),
                preview.getX(), preview.getBottom() + 6, GuiTheme.TEXT, false);
        if (expanded && selected != null) {
            graphics.drawString(font, Component.translatable("gui.unrealtrainmod.models.dimensions",
                            String.format(java.util.Locale.ROOT, "%.2f", selected.length()),
                            String.format(java.util.Locale.ROOT, "%.2f", selected.width()),
                            String.format(java.util.Locale.ROOT, "%.2f", selected.height())),
                    preview.getX(), preview.getBottom() + 19, GuiTheme.MUTED, false);
            graphics.drawString(font, Component.translatable("gui.unrealtrainmod.preview.yaw"),
                    yawInput.getX(), yawInput.getY() - 12, GuiTheme.MUTED, false);
        }
        var message = status != null ? status : Component.translatable("gui.unrealtrainmod.models.count", VehicleCatalog.all().size());
        graphics.drawString(font, font.plainSubstrByWidth(message.getString(), panelWidth - 20),
                left + 10, top + panelHeight - 48, statusError ? GuiTheme.ERROR : GuiTheme.MUTED, false);
    }

    @Override
    public void removed() {
        if (preview != null) preview.close();
        super.removed();
    }
}
