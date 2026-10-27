/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.preview;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** Owns only its depth-enabled target and scratch buffer; model resources remain repository-owned. */
public final class TrainPreviewRenderer implements AutoCloseable {
    private final ByteBufferBuilder scratch = new ByteBufferBuilder(1 << 18);
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(scratch);
    private TextureTarget target;

    public void render(GuiGraphics graphics, int x, int y, int width, int height,
                       ClientModelManager.LoadedModel model, PreviewCamera camera) {
        graphics.flush();
        var minecraft = Minecraft.getInstance();
        double scale = minecraft.getWindow().getGuiScale();
        renderScene(model, camera, Math.max(1, (int)Math.ceil(width * scale)), Math.max(1, (int)Math.ceil(height * scale)));

        var state = new GlStateBackup();
        RenderSystem.backupGlState(state);
        var shader = RenderSystem.getShader();
        int texture = RenderSystem.getShaderTexture(0);
        float[] color = RenderSystem.getShaderColor().clone();
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, target.getColorTextureId());
            var vertices = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            var matrix = graphics.pose().last().pose();
            vertices.addVertex(matrix, x, y, 0).setUv(0, 1);
            vertices.addVertex(matrix, x, y + height, 0).setUv(0, 0);
            vertices.addVertex(matrix, x + width, y + height, 0).setUv(1, 0);
            vertices.addVertex(matrix, x + width, y, 0).setUv(1, 1);
            BufferUploader.drawWithShader(vertices.buildOrThrow());
        } finally {
            RenderSystem.setShaderTexture(0, texture);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.restoreGlState(state);
        }
    }

    private void renderScene(ClientModelManager.LoadedModel model, PreviewCamera camera, int width, int height) {
        var minecraft = Minecraft.getInstance();
        var state = new GlStateBackup();
        RenderSystem.backupGlState(state);
        int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int viewportX = GlStateManager.Viewport.x(), viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width(), viewportHeight = GlStateManager.Viewport.height();
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        var shader = RenderSystem.getShader();
        int texture0 = RenderSystem.getShaderTexture(0), texture1 = RenderSystem.getShaderTexture(1), texture2 = RenderSystem.getShaderTexture(2);
        float[] color = RenderSystem.getShaderColor().clone();
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        float[] clearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            RenderSystem.disableScissor();
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            if (target == null) {
                target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
                target.setClearColor(0.045f, 0.065f, 0.09f, 1);
            } else if (target.width != width || target.height != height) {
                target.resize(width, height, Minecraft.ON_OSX);
            }
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.resetTextureMatrix();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.defaultBlendFunc();
            minecraft.gameRenderer.lightTexture().turnOnLightLayer();
            minecraft.gameRenderer.overlayTexture().setupOverlayColor();

            var bounds = model.bounds();
            float radius = bounds.radius();
            float aspect = (float)width / height;
            float halfFov = (float)Math.toRadians(22.5);
            float limitingFov = Math.min(halfFov, (float)Math.atan(Math.tan(halfFov) * aspect));
            float distance = radius / (float)Math.sin(limitingFov) * 1.1f / camera.zoom();
            RenderSystem.setProjectionMatrix(new Matrix4f().perspective(halfFov * 2, aspect,
                    0.05f, distance + radius * 4 + 1), VertexSorting.DISTANCE_TO_ORIGIN);
            var pose = new PoseStack();
            pose.translate(camera.panX() * radius, camera.panY() * radius, -distance);
            pose.mulPose(Axis.XP.rotationDegrees(camera.pitch()));
            pose.mulPose(Axis.YP.rotationDegrees(camera.yaw()));
            pose.translate(-bounds.centerX(), -bounds.centerY(), -bounds.centerZ());
            TrainModelRenderer.render(model, pose, buffers, LightTexture.FULL_BRIGHT);
            buffers.endBatch();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setTextureMatrix(textureMatrix);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            RenderSystem.setShaderTexture(0, texture0);
            RenderSystem.setShaderTexture(1, texture1);
            RenderSystem.setShaderTexture(2, texture2);
            RenderSystem.activeTexture(activeTexture);
            RenderSystem.bindTexture(boundTexture);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            RenderSystem.clearDepth(clearDepth);
            RenderSystem.restoreGlState(state);
        }
    }

    @Override
    public void close() {
        try {
            if (target != null) {
                int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                try {
                    target.destroyBuffers();
                    target = null;
                } finally {
                    GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
                    GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
                }
            }
        } finally {
            scratch.close();
        }
    }
}
