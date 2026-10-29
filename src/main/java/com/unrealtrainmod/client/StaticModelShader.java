/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/** Local geometry stays on the GPU; only these per-entity uniforms change between draws. */
public final class StaticModelShader {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaticModelShader.class);
    // AlphaPass values shared with static_model.fsh.
    public static final int LEGACY_CUTOUT = 0, MIXED_OPAQUE = 1, BLENDED = 2;
    public static final int FORCE_OPAQUE = 3, FORCE_CUTOUT = 4, FORCE_TRANSLUCENT = 5;
    // XYZ (12) + RGBA (4) + UV (8) + signed-byte normal and padding (4) = 28 bytes.
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1).build();
    private static State state;
    private static boolean cutoutFallbackWarned;

    private StaticModelShader() {}

    public static void register(RegisterShadersEvent event) {
        state = null;
        cutoutFallbackWarned = false;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ClientModelManager.id("static_model"), FORMAT),
                    loaded -> {
                        Uniform model = loaded.getUniform("ModelMat"), normal = loaded.getUniform("NormalMat");
                        Uniform light = loaded.getUniform("LightUV"), overlay = loaded.getUniform("OverlayUV");
                        Uniform alphaPass = loaded.getUniform("AlphaPass");
                        if (model == null || normal == null || light == null || overlay == null || alphaPass == null) {
                            LOGGER.error("Static model shader is missing required uniforms; using buffered rendering");
                        } else {
                            // Minecraft owns and closes the shader; retain only the registered callback's instance.
                            state = new State(loaded, model, normal, light, overlay, alphaPass);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Cannot load static model shader; using buffered rendering", e);
        }
    }

    public static State state() { return state; }

    /** One warning per shader reload, shared by world and preview queues. */
    public static void warnCutoutFallback() {
        if (cutoutFallbackWarned) return;
        cutoutFallbackWarned = true;
        LOGGER.warn("Static model shader unavailable: forced CUTOUT falls back to texture-alpha clipping at 0.1; "
                + "material and vertex alpha no longer affect clipping");
    }

    public record State(ShaderInstance shader, Uniform modelMatrix, Uniform normalMatrix,
                        Uniform light, Uniform overlay, Uniform alphaPass) {
        /** Uses the AlphaPass constants above for both GPU and buffered rendering. */
        public void prepare(PoseStack.Pose pose, int packedLight, int pass) {
            modelMatrix.set(pose.pose());
            normalMatrix.set(pose.normal());
            light.set(packedLight & 0xffff, packedLight >>> 16 & 0xffff);
            overlay.set(OverlayTexture.NO_OVERLAY & 0xffff, OverlayTexture.NO_OVERLAY >>> 16 & 0xffff);
            alphaPass.set(pass);
        }
    }
}
