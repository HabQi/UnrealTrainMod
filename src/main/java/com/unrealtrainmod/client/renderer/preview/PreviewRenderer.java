/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.preview;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.unrealtrainmod.model.ModelBounds;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Shared depth-enabled GUI target; callers retain ownership of their scene geometry. */
public final class PreviewRenderer implements AutoCloseable {
    // Base framing stays at 40x16 blocks; sample scales only change pixel density.
    public static final int THUMBNAIL_WIDTH = 640;
    public static final int THUMBNAIL_HEIGHT = 256;
    public static final float THUMBNAIL_PIXELS_PER_BLOCK = 16;
    public static final int VEHICLE_THUMBNAIL_SAMPLE_SCALE = 2;
    public static final int RAIL_THUMBNAIL_SAMPLE_SCALE = 3;
    public static final float THUMBNAIL_DISTANCE = 128;
    private TextureTarget target;

    public void render(GuiGraphics graphics, int x, int y, int width, int height,
                       ModelBounds bounds, PreviewCamera camera, Consumer<PoseStack> drawScene) {
        graphics.flush();
        var minecraft = Minecraft.getInstance();
        double scale = minecraft.getWindow().getGuiScale();
        int targetWidth = Math.max(1, (int)Math.ceil(width * scale));
        int targetHeight = Math.max(1, (int)Math.ceil(height * scale));
        renderScene(targetWidth, targetHeight, false, () -> {
            drawScene.accept(perspectivePose(bounds, camera, targetWidth, targetHeight));
            return null;
        });

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

    /** Returns owned, upright RGBA pixels with only the outer transparent margin removed. */
    public NativeImage capture(ModelBounds bounds, float yaw, float pitch, boolean translucent,
                               int sampleScale, Consumer<PoseStack> drawScene) {
        int width = THUMBNAIL_WIDTH * sampleScale;
        int height = THUMBNAIL_HEIGHT * sampleScale;
        return renderScene(width, height, true, () -> {
            drawScene.accept(thumbnailPose(bounds, yaw, pitch));
            int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
            int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
            int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
            try (var pixels = new NativeImage(width, height, false)) {
                GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
                RenderSystem.bindTexture(target.getColorTextureId());
                pixels.downloadTexture(0, false);
                pixels.flipY();
                return trim(pixels, translucent);
            } finally {
                GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, rowLength);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, skipRows);
                GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            }
        });
    }

    private static PoseStack perspectivePose(ModelBounds bounds, PreviewCamera camera, int width, int height) {
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
        return pose;
    }

    private static PoseStack thumbnailPose(ModelBounds bounds, float yaw, float pitch) {
        float halfWidth = THUMBNAIL_WIDTH / (2 * THUMBNAIL_PIXELS_PER_BLOCK);
        float halfHeight = THUMBNAIL_HEIGHT / (2 * THUMBNAIL_PIXELS_PER_BLOCK);
        RenderSystem.setProjectionMatrix(new Matrix4f().ortho(-halfWidth, halfWidth,
                -halfHeight, halfHeight, 0.05f, THUMBNAIL_DISTANCE * 2), VertexSorting.DISTANCE_TO_ORIGIN);
        var pose = new PoseStack();
        pose.translate(0, 0, -THUMBNAIL_DISTANCE);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        // The bounds only center the model; neither framing nor magnification depends on their size.
        pose.translate(-bounds.centerX(), -bounds.centerY(), -bounds.centerZ());
        return pose;
    }

    private static NativeImage trim(NativeImage source, boolean translucent) {
        int minX = source.getWidth(), minY = source.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if ((source.getPixelRGBA(x, y) >>> 24) == 0) continue;
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) throw new IllegalStateException("Model thumbnail is fully transparent");
        var image = new NativeImage(maxX - minX + 1, maxY - minY + 1, false);
        try {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = source.getPixelRGBA(minX + x, minY + y);
                    int alpha = pixel >>> 24;
                    // Offscreen translucency produces premultiplied RGB; PNG and GUI blits use straight alpha.
                    if (!translucent && alpha > 0) {
                        // Rail cutout pixels are opaque wherever the shader kept the fragment.
                        pixel |= 0xff000000;
                    } else if (alpha > 0 && alpha < 255) {
                        int red = Math.min(255, ((pixel & 255) * 255 + alpha / 2) / alpha);
                        int green = Math.min(255, (((pixel >>> 8) & 255) * 255 + alpha / 2) / alpha);
                        int blue = Math.min(255, (((pixel >>> 16) & 255) * 255 + alpha / 2) / alpha);
                        pixel = alpha << 24 | blue << 16 | green << 8 | red;
                    }
                    image.setPixelRGBA(x, y, pixel);
                }
            }
            return image;
        } catch (RuntimeException | Error exception) {
            image.close();
            throw exception;
        }
    }

    private <T> T renderScene(int width, int height, boolean transparent, Supplier<T> drawScene) {
        var minecraft = Minecraft.getInstance();
        var state = new GlStateBackup();
        RenderSystem.backupGlState(state);
        int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int viewportX = GlStateManager.Viewport.x(), viewportY = GlStateManager.Viewport.y();
        int viewportWidth = GlStateManager.Viewport.width(), viewportHeight = GlStateManager.Viewport.height();
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int[] boundTextures = new int[3];
        for (int i = 0; i < boundTextures.length; i++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            boundTextures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        RenderSystem.activeTexture(activeTexture);
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
            } else if (target.width != width || target.height != height) {
                target.resize(width, height, Minecraft.ON_OSX);
            }
            if (transparent) target.setClearColor(0, 0, 0, 0);
            else target.setClearColor(0.045f, 0.065f, 0.09f, 1);
            RenderSystem.clearDepth(1);
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

            return drawScene.get();
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
            for (int i = 0; i < boundTextures.length; i++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                RenderSystem.bindTexture(boundTextures[i]);
            }
            RenderSystem.activeTexture(activeTexture);
            RenderSystem.bindTexture(boundTexture);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            RenderSystem.clearDepth(clearDepth);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, packAlignment);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GlStateManager._glUseProgram(program);
            RenderSystem.restoreGlState(state);
        }
    }

    @Override
    public void close() {
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
    }
}
