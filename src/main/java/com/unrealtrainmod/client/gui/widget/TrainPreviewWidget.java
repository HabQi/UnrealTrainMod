/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.widget;

import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.client.renderer.preview.TrainPreviewRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public final class TrainPreviewWidget extends AbstractWidget implements AutoCloseable {
    private final PreviewCamera camera;
    private final TrainPreviewRenderer renderer = new TrainPreviewRenderer();
    private ResourceLocation modelId;

    public TrainPreviewWidget(PreviewCamera camera, ResourceLocation modelId) {
        super(0, 0, 100, 100, Component.translatable("gui.unrealtrainmod.preview.title"));
        this.camera = camera;
        this.modelId = modelId;
        setTooltip(Tooltip.create(Component.translatable("gui.unrealtrainmod.preview.help")));
    }

    public void setModel(ResourceLocation id) { modelId = id; }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.panel(graphics, getX(), getY(), width, height, GuiTheme.PANEL);
        var model = ClientModelManager.INSTANCE.model(modelId);
        if (model == null) {
            graphics.drawWordWrap(Minecraft.getInstance().font,
                    Component.translatable(modelId == null ? "gui.unrealtrainmod.models.no_packages" : "gui.unrealtrainmod.preview.missing"),
                    getX() + 8, getY() + 8, width - 16, modelId == null ? GuiTheme.MUTED : GuiTheme.ERROR);
        } else {
            renderer.render(graphics, getX() + 1, getY() + 1, width - 2, height - 2, model, camera);
        }
        if (isFocused()) graphics.renderOutline(getX(), getY(), width, height, GuiTheme.ACCENT);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (Screen.hasShiftDown()) camera.pan((float)(dragX / width * 2), (float)(-dragY / height * 2));
        else camera.rotate(dragX * 0.8, dragY * 0.8);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!active || !visible || !isMouseOver(mouseX, mouseY)) return false;
        camera.setZoom(camera.zoom() * Math.pow(1.1, scrollY));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> camera.rotate(-5, 0);
            case GLFW.GLFW_KEY_RIGHT -> camera.rotate(5, 0);
            case GLFW.GLFW_KEY_UP -> camera.rotate(0, -5);
            case GLFW.GLFW_KEY_DOWN -> camera.rotate(0, 5);
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> camera.setZoom(camera.zoom() + 0.05);
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> camera.setZoom(camera.zoom() - 0.05);
            case GLFW.GLFW_KEY_HOME -> camera.reset();
            default -> { return false; }
        }
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        output.add(NarratedElementType.USAGE, Component.translatable("gui.unrealtrainmod.preview.help"));
    }

    @Override public void close() { renderer.close(); }
}
