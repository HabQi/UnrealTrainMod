/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * One track draw pass: reuses RenderType, shader program, samplers and light directions across
 * consecutive cached meshes, then restores the caller's render state, program, textures and buffers.
 *
 * <p>Render types are still entered and left in submission order, and every mesh uploads its own
 * model-view matrix because the world renderer reuses a single mutable matrix instance.
 */
final class TrackRenderState implements AutoCloseable {
    // Vertex normals are already in world space. Do not inherit the previous GUI/entity light directions.
    private static final Vector3f LIGHT_0 = new Vector3f(0.2f, 1, -0.7f).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(-0.2f, 1, 0.7f).normalize();
    // Samplers 0..2 are the surface, overlay and lightmap textures touched by the track render types.
    private static final int TEXTURE_UNITS = 3;

    private final TrackRenderStats stats;
    private final GlStateBackup previousState = new GlStateBackup();
    private final ShaderInstance previousShader = RenderSystem.getShader();
    private final float[] previousColor = RenderSystem.getShaderColor().clone();
    private final int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] previousShaderTextures = new int[TEXTURE_UNITS];
    private final int[] previousBoundTextures = new int[TEXTURE_UNITS];
    private final Matrix4f appliedProjection = new Matrix4f();
    private RenderType activeType;
    private ShaderInstance appliedShader;
    private ShaderInstance lightShader;
    private int appliedProgram = -1;
    private boolean projectionApplied;

    TrackRenderState(TrackRenderStats stats) {
        this.stats = stats;
        RenderSystem.backupGlState(previousState);
        for (int i = 0; i < TEXTURE_UNITS; i++) {
            previousShaderTextures[i] = RenderSystem.getShaderTexture(i);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            previousBoundTextures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        RenderSystem.activeTexture(previousActiveTexture);
    }

    /** Native quads: fixed face shading, no surface texture, the vertex format carries packed light. */
    void drawNative(VertexBuffer buffer, Matrix4f modelView, Matrix4f projection) {
        begin(TrackRenderTypes.OPAQUE, VertexFormat.Mode.QUADS, modelView, projection);
        buffer.bind();
        buffer.draw();
    }

    /** Authored rail batch: its own texture and cull mode, fixed world-space direction lights. */
    void drawTextured(RenderType type, VertexBuffer buffer, Matrix4f modelView, Matrix4f projection) {
        begin(type, VertexFormat.Mode.TRIANGLES, modelView, projection);
        buffer.bind();
        buffer.draw();
    }

    private void begin(RenderType type, VertexFormat.Mode mode, Matrix4f modelView, Matrix4f projection) {
        boolean typeChanged = type != activeType;
        if (typeChanged) {
            if (activeType != null) activeType.clearRenderState();
            type.setupRenderState();
            activeType = type;
            if (stats != null) stats.renderTypeSetup();
        }
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != appliedShader || typeChanged) {
            // Refresh default uniforms and samplers whenever the shader or RenderType changes.
            shader.setDefaultUniforms(mode, modelView, projection, Minecraft.getInstance().getWindow());
            boolean programSwitch = shader != appliedShader && appliedProgram != -1 && appliedProgram != shader.getId();
            applyLights(shader);
            shader.apply();
            appliedShader = shader;
            appliedProgram = shader.getId();
            appliedProjection.set(projection);
            projectionApplied = true;
            if (stats != null) stats.shaderBind(programSwitch);
            return;
        }
        // Same program and render state: only the per-mesh model-view matrix changes.
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(modelView);
            shader.MODEL_VIEW_MATRIX.upload();
        }
        if (!projectionApplied || !appliedProjection.equals(projection)) {
            if (shader.PROJECTION_MATRIX != null) {
                shader.PROJECTION_MATRIX.set(projection);
                shader.PROJECTION_MATRIX.upload();
            }
            appliedProjection.set(projection);
            projectionApplied = true;
        }
    }

    /** Keeps the rail model's fixed world direction lights while the scope is open. */
    private void applyLights(ShaderInstance shader) {
        if (shader.LIGHT0_DIRECTION == null && shader.LIGHT1_DIRECTION == null) return;
        if (shader.LIGHT0_DIRECTION != null) shader.LIGHT0_DIRECTION.set(LIGHT_0);
        if (shader.LIGHT1_DIRECTION != null) shader.LIGHT1_DIRECTION.set(LIGHT_1);
        lightShader = shader;
    }

    /**
     * Restores the engine's shader light directions on the shader this scope overrode. Only the uniform
     * values are reset; the caller reapplies them, as in the shared model state handling.
     */
    private void restoreLights() {
        if (lightShader == null) return;
        RenderSystem.setupShaderLights(lightShader);
        lightShader = null;
    }

    @Override
    public void close() {
        long start = stats == null ? 0L : System.nanoTime();
        try {
            if (activeType != null) {
                activeType.clearRenderState();
                activeType = null;
            }
            if (appliedShader != null) {
                restoreLights();
                appliedShader.clear();
                appliedShader = null;
            }
        } finally {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            for (int i = 0; i < TEXTURE_UNITS; i++) {
                RenderSystem.setShaderTexture(i, previousShaderTextures[i]);
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                RenderSystem.bindTexture(previousBoundTextures[i]);
            }
            RenderSystem.activeTexture(previousActiveTexture);
            RenderSystem.setShader(() -> previousShader);
            GlStateManager._glUseProgram(previousProgram);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            RenderSystem.restoreGlState(previousState);
            if (stats != null) stats.drawCleanup(System.nanoTime() - start);
        }
    }
}
