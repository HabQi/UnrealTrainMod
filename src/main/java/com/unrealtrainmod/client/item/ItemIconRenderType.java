/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.item;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Captured pixels already contain lighting; GUI presentation must not shade them again. */
final class ItemIconRenderType extends RenderStateShard {
    private ItemIconRenderType() { super("utm_item_icon", () -> {}, () -> {}); }

    static RenderType create(ResourceLocation texture) {
        return RenderType.create("utm_item_icon", DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 256,
                false, false, RenderType.CompositeState.builder()
                        .setShaderState(POSITION_TEX_SHADER)
                        .setTextureState(new TextureStateShard(texture, true, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL).setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_DEPTH_WRITE).createCompositeState(false));
    }
}
