/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.StaticModelShader;
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

/** Protects the caller's state without changing framebuffer or projection. */
final class ModelRenderState implements AutoCloseable {
    // Preview poses transform normals into view space; +Z points toward the window's viewer.
    private static final Vector3f FRONT_LIGHT = new Vector3f(0, 0, 1);
    private final boolean frontLighting;
    private final GlStateBackup state = new GlStateBackup();
    private final ShaderInstance shader = RenderSystem.getShader();
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] shaderTextures = new int[3], boundTextures = new int[3];
    private final StaticModelShader.State custom = StaticModelShader.state();
    private RenderType active;
    private ShaderInstance boundShader;
    private PoseStack.Pose gpuPose;
    private int gpuLight, gpuPass;
    private boolean texturesDirty;

    ModelRenderState(boolean frontLighting) {
        this.frontLighting = frontLighting;
        RenderSystem.backupGlState(state);
        for (int i = 0; i < 3; i++) {
            shaderTextures[i] = RenderSystem.getShaderTexture(i);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            boundTextures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        RenderSystem.activeTexture(activeTexture);
    }

    void use(RenderType type, boolean blended, boolean doubleSided) {
        if (active != type) {
            if (active != null) active.clearRenderState();
            active = type;
            active.setupRenderState();
            texturesDirty = true;
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(!blended);
            RenderSystem.colorMask(true, true, true, true);
            if (doubleSided) RenderSystem.disableCull();
            else RenderSystem.enableCull();
        }
    }

    /** RenderTypes select engine shaders but do not bind a GL program; keep our shader across texture changes. */
    void prepareGpu(PoseStack.Pose pose, int light, int pass, Matrix4f modelView, Matrix4f projection) {
        if (boundShader == null) {
            custom.prepare(pose, light, pass);
            custom.shader().setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection,
                    Minecraft.getInstance().getWindow());
            setupLights(custom.shader());
            boundShader = custom.shader();
            boundShader.apply();
        } else {
            if (texturesDirty) bindGpuTextures();
            // Uniform.upload does not skip unchanged values in this engine version.
            if (gpuPose != pose) {
                custom.modelMatrix().set(pose.pose());
                custom.normalMatrix().set(pose.normal());
                custom.modelMatrix().upload();
                custom.normalMatrix().upload();
            }
            if (gpuLight != light) {
                custom.light().set(light & 0xffff, light >>> 16 & 0xffff);
                custom.light().upload();
            }
            if (gpuPass != pass) {
                custom.alphaPass().set(pass);
                custom.alphaPass().upload();
            }
        }
        gpuPose = pose;
        gpuLight = light;
        gpuPass = pass;
        texturesDirty = false;
    }

    private void bindGpuTextures() {
        int previous = GlStateManager._getActiveTexture();
        for (int i = 0; i < 3; i++) {
            int texture = RenderSystem.getShaderTexture(i);
            boundShader.setSampler("Sampler" + i, texture);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            RenderSystem.bindTexture(texture);
        }
        RenderSystem.activeTexture(previous);
    }

    /** CPU fallback owns its shader binding; the next GPU segment must apply its shader again. */
    void endGpu() {
        if (boundShader != null) {
            boundShader.clear();
            restoreLights(boundShader);
            boundShader = null;
        }
    }

    void setupLights(ShaderInstance shader) {
        if (!frontLighting) return;
        if (shader.LIGHT0_DIRECTION != null) shader.LIGHT0_DIRECTION.set(FRONT_LIGHT);
        if (shader.LIGHT1_DIRECTION != null) shader.LIGHT1_DIRECTION.set(FRONT_LIGHT);
    }

    void restoreLights(ShaderInstance shader) {
        if (frontLighting) RenderSystem.setupShaderLights(shader);
    }

    @Override
    public void close() {
        try {
            endGpu();
            if (active != null) active.clearRenderState();
        } finally {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            for (int i = 0; i < 3; i++) {
                RenderSystem.setShaderTexture(i, shaderTextures[i]);
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                RenderSystem.bindTexture(boundTextures[i]);
            }
            RenderSystem.activeTexture(activeTexture);
            RenderSystem.setShader(() -> shader);
            GlStateManager._glUseProgram(program);
            RenderSystem.restoreGlState(state);
        }
    }
}
