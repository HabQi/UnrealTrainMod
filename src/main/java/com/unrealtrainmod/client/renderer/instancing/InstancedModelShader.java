/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.instancing;

import com.mojang.blaze3d.shaders.Uniform;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.StaticModelShader;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Instanced variant of {@link StaticModelShader}; the fragment stage is shared. */
public final class InstancedModelShader {
    private static final Logger LOGGER = LoggerFactory.getLogger(InstancedModelShader.class);
    private static final String[] ATTRIBUTE_NAMES = {
            "InstModel0", "InstModel1", "InstModel2", "InstModel3",
            "InstNormal0", "InstNormal1", "InstNormal2", "InstLightUV"
    };
    public static final int ATTRIBUTE_COUNT = ATTRIBUTE_NAMES.length;
    private static State state;
    private static boolean capabilitiesChecked, instancingCapable;

    private InstancedModelShader() {}

    public static void register(RegisterShadersEvent event) {
        state = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ClientModelManager.id("instanced_model"), StaticModelShader.FORMAT), loaded -> {
                Uniform overlay = loaded.getUniform("OverlayUV"), alphaPass = loaded.getUniform("AlphaPass");
                Uniform directional = loaded.getUniform("DirectionalLighting");
                Uniform renderOrigin = loaded.getUniform("RenderOrigin");
                int[] attributes = attributeLocations(loaded);
                if (overlay == null || alphaPass == null || directional == null || renderOrigin == null || attributes == null) {
                    LOGGER.error("Instanced model shader is missing required uniforms or instance attributes; "
                            + "using normal vehicle rendering");
                } else {
                    // Minecraft owns and closes the shader program; keep only the registered callback's instance.
                    state = new State(loaded, overlay, alphaPass, directional, renderOrigin, attributes);
                }
            });
        } catch (IOException e) {
            LOGGER.error("Cannot load instanced model shader; using normal vehicle rendering", e);
        }
    }

    public static State state() { return state; }

    /** OpenGL 3.3 is required for attrib divisors; a running game does not guarantee that by itself. */
    public static boolean usable() { return state != null && instancingCapable(); }

    private static boolean instancingCapable() {
        if (!capabilitiesChecked) {
            try {
                instancingCapable = GL.getCapabilities().OpenGL33;
                capabilitiesChecked = true;
            } catch (IllegalStateException e) {
                // Capabilities appear with the client window; retry on the next draw instead of caching a failure.
                return false;
            }
        }
        return instancingCapable;
    }

    /** Locations are queried after link so the instanced attributes never assume fixed slots. */
    private static int[] attributeLocations(ShaderInstance shader) {
        int reserved = StaticModelShader.FORMAT.getElements().size();
        int[] locations = new int[ATTRIBUTE_COUNT];
        for (int i = 0; i < ATTRIBUTE_COUNT; i++) {
            int location = GL20.glGetAttribLocation(shader.getId(), ATTRIBUTE_NAMES[i]);
            if (location < reserved) return null;
            for (int j = 0; j < i; j++) if (locations[j] == location) return null;
            locations[i] = location;
        }
        return locations;
    }

    public record State(ShaderInstance shader, Uniform overlay, Uniform alphaPass,
                        Uniform directionalLighting, Uniform renderOrigin, int[] attributes) {
        /** Batch-level values; matrices and light come from the instance attributes. */
        public void prepare(int pass, boolean directional) {
            overlay.set(OverlayTexture.NO_OVERLAY & 0xffff, OverlayTexture.NO_OVERLAY >>> 16 & 0xffff);
            alphaPass.set(pass);
            directionalLighting.set(directional ? 1 : 0);
            renderOrigin.set(0f, 0f, 0f);
        }
    }
}
