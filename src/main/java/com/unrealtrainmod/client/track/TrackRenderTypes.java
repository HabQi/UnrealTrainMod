/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

final class TrackRenderTypes extends RenderStateShard {
    private record TextureKey(ResourceLocation texture, boolean doubleSided) {}
    private static final Map<TextureKey, RenderType> TEXTURED = new HashMap<>();

    static RenderType textured(ResourceLocation texture, boolean doubleSided) {
        return TEXTURED.computeIfAbsent(new TextureKey(texture, doubleSided), key -> RenderType.create(
                "utm_rail_model", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1536, false, false,
                RenderType.CompositeState.builder().setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(NO_TRANSPARENCY).setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_DEPTH_WRITE).setCullState(doubleSided ? NO_CULL : CULL)
                        .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY).createCompositeState(false)));
    }

    static void clearTextures() { TEXTURED.clear(); }
    static final RenderType OPAQUE = RenderType.create("utm_track", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            VertexFormat.Mode.QUADS, 1536, false, false, RenderType.CompositeState.builder()
                    // This vanilla shader consumes packed block/sky UV2 and needs no surface texture or normals.
                    .setShaderState(RENDERTYPE_TEXT_BACKGROUND_SHADER)
                    .setLightmapState(LIGHTMAP)
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setCullState(CULL).createCompositeState(false));

    // Use the main world target in Fabulous graphics too; the later item target is cleared by vanilla.
    static final RenderType PREVIEW = RenderType.create("utm_track_preview", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 4096, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.of(2)))
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL).createCompositeState(false));

    private TrackRenderTypes() { super("utm_track", () -> {}, () -> {}); }
}
