/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Vanilla entity shader suppliers let Iris choose the current view's program. */
public final class IrisRenderTypes extends RenderStateShard {
    private record Key(ResourceLocation texture, boolean doubleSided, boolean translucent, boolean opaque) {}
    private static final Map<Key, RenderType> TYPES = new HashMap<>();

    private IrisRenderTypes() { super("utm_iris", () -> {}, () -> {}); }

    public static RenderType get(ResourceLocation texture, boolean doubleSided, boolean translucent, boolean opaque) {
        return TYPES.computeIfAbsent(new Key(texture, doubleSided, translucent, opaque), key -> RenderType.create(
                "utm_iris_entity", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1536, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(opaque ? RENDERTYPE_ENTITY_SOLID_SHADER : translucent
                                ? RENDERTYPE_ENTITY_TRANSLUCENT_SHADER : RENDERTYPE_ENTITY_CUTOUT_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(translucent ? TRANSLUCENT_TRANSPARENCY : NO_TRANSPARENCY)
                        .setCullState(doubleSided ? NO_CULL : CULL)
                        .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false)));
    }

    public static void clear() { TYPES.clear(); IrisCompat.clearTypes(); }
}
