/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Scene-local ordering is authoritative; these types never sort or redirect the current framebuffer. */
final class ModelRenderTypes extends RenderStateShard {
    private record Key(ResourceLocation texture, boolean doubleSided, boolean blended, boolean opaque) {}
    private static final Map<Key, RenderType> TYPES = new HashMap<>();

    private ModelRenderTypes() { super("train_model", () -> {}, () -> {}); }

    static RenderType get(ResourceLocation texture, boolean doubleSided, boolean blended, boolean opaque) {
        return TYPES.computeIfAbsent(new Key(texture, doubleSided, blended, opaque), key -> RenderType.create(
                "train_model", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1536, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(opaque ? RENDERTYPE_ENTITY_SOLID_SHADER
                                : blended ? RENDERTYPE_ENTITY_TRANSLUCENT_SHADER : RENDERTYPE_ENTITY_CUTOUT_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(blended ? TRANSLUCENT_TRANSPARENCY : NO_TRANSPARENCY)
                        .setCullState(doubleSided ? NO_CULL : CULL)
                        .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)
                        .setWriteMaskState(blended ? COLOR_WRITE : COLOR_DEPTH_WRITE)
                        .createCompositeState(false)));
    }
}
