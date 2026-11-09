/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.unrealtrainmod.client.gui.theme.GuiTheme;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.client.renderer.preview.PreviewRenderer;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.wiring.ConnectorData;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

final class WiringPreviewWidget extends AbstractWidget implements AutoCloseable {
    private final PreviewRenderer renderer = new PreviewRenderer();
    private final PreviewCamera camera = new PreviewCamera();
    private final UUID spanId = UUID.randomUUID();
    private final UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
    private ResourceLocation modelId;
    WiringPreviewWidget() {
        super(0, 0, 100, 100, Component.translatable("wiring.unrealtrainmod.preview"));
        setTooltip(Tooltip.create(Component.translatable("gui.unrealtrainmod.preview.help")));
    }
    void resetView() { camera.reset(); }
    void model(ResourceLocation id) { WiringClient.release(spanId); modelId = id; camera.reset(); }
    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.panel(graphics, getX(), getY(), width, height, GuiTheme.PANEL);
        var model = WiringModelManager.INSTANCE.model(modelId);
        if (model == null) {
            graphics.drawWordWrap(Minecraft.getInstance().font, Component.translatable(modelId == null
                    ? "wiring.unrealtrainmod.empty" : "gui.unrealtrainmod.rail_models.preview_missing"),
                    getX() + 8, getY() + 8, width - 16, modelId == null ? GuiTheme.MUTED : GuiTheme.ERROR);
            return;
        }
        boolean wire = model.definition().wire();
        var bounds = wire ? new ModelBounds(-1, -2, -1, 1, 2, 11) : model.definition().bounds();
        renderer.render(graphics, getX() + 1, getY() + 1, width - 2, height - 2, bounds, camera, pose -> {
            try (var bytes = new ByteBufferBuilder(65536)) {
                var buffers = MultiBufferSource.immediate(bytes);
                var draws = wire ? WiringClient.wireDraws(spanId, model,
                        new ConnectorData(firstId, modelId, Vec3.ZERO, Direction.UP, Vec3.ZERO, Vec3.ZERO, 0),
                        new ConnectorData(secondId, modelId, new Vec3(0, 0, 10), Direction.UP, Vec3.ZERO, Vec3.ZERO, 0),
                        Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime(), partialTick)
                        : model.draws();
                if (draws != null) WiringGeometry.render(draws, pose, buffers, 0xF000F0);
                if (!wire && model.definition().empty()) net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose,
                        buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines()), -.125, -.125, -.125, .125, .125, .125, .2f, 1, 1, 1);
                buffers.endBatch();
            }
        });
        if (isFocused()) graphics.renderOutline(getX(), getY(), width, height, GuiTheme.ACCENT);
    }
    @Override protected void onDrag(double mouseX, double mouseY, double dx, double dy) {
        if (Screen.hasShiftDown()) camera.pan((float)(dx / width * 2), (float)(-dy / height * 2));
        else camera.rotate(dx * .8, dy * .8);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (!active || !visible || !isMouseOver(x, y)) return false;
        camera.setZoom(camera.zoom() * Math.pow(1.1, sy)); return true;
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> camera.rotate(-5, 0);
            case GLFW.GLFW_KEY_RIGHT -> camera.rotate(5, 0);
            case GLFW.GLFW_KEY_UP -> camera.rotate(0, -5);
            case GLFW.GLFW_KEY_DOWN -> camera.rotate(0, 5);
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> camera.setZoom(camera.zoom() + .05);
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> camera.setZoom(camera.zoom() - .05);
            case GLFW.GLFW_KEY_HOME -> resetView();
            default -> { return false; }
        }
        return true;
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, getMessage());
        out.add(NarratedElementType.USAGE, Component.translatable("gui.unrealtrainmod.preview.help"));
    }
    @Override public void close() { WiringClient.release(spanId); renderer.close(); }
}
