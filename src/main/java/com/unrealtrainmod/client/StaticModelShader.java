/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.unrealtrainmod.UnrealTrainMod;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/** Local geometry stays on the GPU; only these per-entity uniforms change between draws. */
public final class StaticModelShader {
    // XYZ (12) + RGBA (4) + UV (8) + signed-byte normal and padding (4) = 28 bytes.
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1).build();
    private static State state;

    private StaticModelShader() {}

    public static void register(RegisterShadersEvent event) {
        state = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ClientModelManager.id("static_model"), FORMAT),
                    loaded -> {
                        Uniform model = loaded.getUniform("ModelMat"), normal = loaded.getUniform("NormalMat");
                        Uniform light = loaded.getUniform("LightUV"), overlay = loaded.getUniform("OverlayUV");
                        if (model == null || normal == null || light == null || overlay == null) {
                            UnrealTrainMod.LOGGER.error("Static model shader is missing required uniforms; using buffered rendering");
                        } else {
                            // Minecraft owns and closes the shader; retain only the registered callback's instance.
                            state = new State(loaded, model, normal, light, overlay);
                        }
                    });
        } catch (IOException e) {
            UnrealTrainMod.LOGGER.error("Cannot load static model shader; using buffered rendering", e);
        }
    }

    public static State state() { return state; }

    public record State(ShaderInstance shader, Uniform modelMatrix, Uniform normalMatrix, Uniform light, Uniform overlay) {
        public void prepare(PoseStack.Pose pose, int packedLight) {
            modelMatrix.set(pose.pose());
            normalMatrix.set(pose.normal());
            light.set(packedLight & 0xffff, packedLight >>> 16 & 0xffff);
            overlay.set(OverlayTexture.NO_OVERLAY & 0xffff, OverlayTexture.NO_OVERLAY >>> 16 & 0xffff);
        }
    }
}
