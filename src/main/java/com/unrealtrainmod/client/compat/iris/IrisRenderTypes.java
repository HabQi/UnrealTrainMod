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
    private record Key(ResourceLocation texture, boolean doubleSided, boolean translucent, boolean opaque,
                       boolean temporal, boolean emissive) {}
    private static final Map<Key, RenderType> TYPES = new HashMap<>();

    private IrisRenderTypes() { super("utm_iris", () -> {}, () -> {}); }

    public static RenderType get(ResourceLocation texture, boolean doubleSided, boolean translucent, boolean opaque) {
        return get(texture, doubleSided, translucent, opaque, false);
    }

    static RenderType get(ResourceLocation texture, boolean doubleSided, boolean translucent, boolean opaque, boolean temporal) {
        return get(texture, doubleSided, translucent, opaque, temporal, false);
    }

    static RenderType get(ResourceLocation texture, boolean doubleSided, boolean translucent, boolean opaque,
                          boolean temporal, boolean emissive) {
        return TYPES.computeIfAbsent(new Key(texture, doubleSided, translucent, opaque, temporal, emissive), key -> RenderType.create(
                temporal ? "utm_iris_vehicle_history" : "utm_iris_entity",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1536, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(emissive ? RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER
                                : opaque ? RENDERTYPE_ENTITY_SOLID_SHADER : translucent
                                ? RENDERTYPE_ENTITY_TRANSLUCENT_SHADER : RENDERTYPE_ENTITY_CUTOUT_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(translucent ? TRANSLUCENT_TRANSPARENCY : NO_TRANSPARENCY)
                        .setCullState(doubleSided ? NO_CULL : CULL)
                        .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)
                        .setLayeringState(temporal || emissive ? new LayeringStateShard("utm_vehicle_material",
                                () -> {
                                    IrisVehicleReprojection.setDrawing(temporal);
                                    IrisEmissivePass.setDrawing(emissive);
                                }, () -> {
                                    IrisEmissivePass.setDrawing(false);
                                    IrisVehicleReprojection.setDrawing(false);
                                }) : NO_LAYERING)
                        .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false)));
    }

    public static void clear() { TYPES.clear(); IrisCompat.clearTypes(); }
}
