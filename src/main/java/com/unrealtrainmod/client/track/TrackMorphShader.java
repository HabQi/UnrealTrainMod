/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.unrealtrainmod.UnrealTrainMod;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.slf4j.LoggerFactory;

/** UV0 carries the reverse endpoint's X/Z; lateral turnout movement has no vertical component. */
public final class TrackMorphShader {
    static final VertexFormat FORMAT = VertexFormat.builder().add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR).add("UV0", VertexFormatElement.UV0)
            .add("UV2", VertexFormatElement.UV2).build();
    private static ShaderInstance shader;
    private TrackMorphShader() {}
    static ShaderInstance shader() { return shader; }

    public static void register(RegisterShadersEvent event) {
        shader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_morph"), FORMAT), loaded -> {
                if (loaded.getUniform("Movement") != null) shader = loaded;
            });
        } catch (IOException exception) {
            LoggerFactory.getLogger(TrackMorphShader.class).error("Cannot load turnout morph shader; using leaf buffer updates", exception);
        }
    }
}
