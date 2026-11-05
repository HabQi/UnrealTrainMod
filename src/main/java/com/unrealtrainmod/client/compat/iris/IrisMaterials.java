/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.ClientModelManager;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Lazily derived alpha masks. The original package pixels and native backend are unchanged. */
public final class IrisMaterials {
    public enum Mask { OPAQUE, SOLID, BLEND, CLIPPED }
    private record Key(ResourceLocation texture, Mask mask) {}
    private static final Map<Key, ResourceLocation> TEXTURES = new HashMap<>();

    private IrisMaterials() {}

    public static ResourceLocation texture(ResourceLocation original, Mask mask) {
        if (original.equals(ClientModelManager.WHITE)) return original;
        return TEXTURES.computeIfAbsent(new Key(original, mask), key -> {
            var manager = Minecraft.getInstance().getTextureManager();
            var source = ((DynamicTexture) manager.getTexture(original)).getPixels();
            var pixels = new NativeImage(source.getWidth(), source.getHeight(), false);
            DynamicTexture texture;
            try {
                for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
                    int pixel = source.getPixelRGBA(x, y), alpha = pixel >>> 24;
                    int result = switch (mask) {
                        case OPAQUE -> 255;
                        case SOLID -> alpha == 255 ? 255 : 0;
                        case BLEND -> alpha >= 26 && alpha < 255 ? alpha : 0;
                        case CLIPPED -> alpha >= 26 ? alpha : 0;
                    };
                    pixels.setPixelRGBA(x, y, (pixel & 0xffffff) | result << 24);
                }
                texture = new DynamicTexture(pixels);
            } catch (RuntimeException | Error exception) {
                pixels.close();
                throw exception;
            }
            var id = ClientModelManager.id("textures/iris/" + mask.name().toLowerCase(java.util.Locale.ROOT)
                    + "/" + original.getNamespace() + "/" + original.getPath());
            try { manager.register(id, texture); }
            catch (RuntimeException | Error exception) { texture.close(); throw exception; }
            return id;
        });
    }

    public static void clear() {
        var manager = Minecraft.getInstance().getTextureManager();
        TEXTURES.values().forEach(manager::release);
        TEXTURES.clear();
        IrisRenderTypes.clear();
    }
}
