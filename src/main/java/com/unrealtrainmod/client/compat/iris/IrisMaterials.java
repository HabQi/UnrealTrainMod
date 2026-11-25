/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.texture.MaterialTexture;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Lazily derived alpha masks. The original package pixels and native backend are unchanged. */
public final class IrisMaterials {
    public enum Mask { OPAQUE, SOLID, BLEND, CLIPPED }
    private record Key(ResourceLocation texture, Mask mask) {}
    private static final Map<Key, ResourceLocation> TEXTURES = new HashMap<>();
    private static final java.util.Set<Key> FAILED = new java.util.HashSet<>();

    private static final Map<Key, com.unrealtrainmod.client.ModelPreparation.Ticket<NativeImage>> PENDING = new HashMap<>();
    private IrisMaterials() {}

    public static boolean ready(ResourceLocation original, Mask mask) {
        if (original.equals(ClientModelManager.WHITE)) return com.unrealtrainmod.client.texture.MaterialTexture.ready(original);
        var key = new Key(original, mask);
        if (FAILED.contains(key)) return MaterialTexture.ready(original);
        if (!TEXTURES.containsKey(key)) {
            var ticket = PENDING.get(key);
            if (ticket == null) {
                var base = (MaterialTexture)Minecraft.getInstance().getTextureManager().getTexture(original);
                var lease = base.retainPixels();
                if (lease == null) return false;
                ticket = com.unrealtrainmod.client.ModelPreparation.geometry(() -> masked(lease.image(), mask),
                        image -> (long)image.getWidth() * image.getHeight() * 4, NativeImage::close);
                if (ticket == null) { lease.close(); return false; }
                ticket.onRelease(lease::close); PENDING.put(key, ticket);
            }
            if (!ticket.ready()) return false;
            NativeImage pixels;
            try { pixels = ticket.take(); }
            catch (RuntimeException exception) {
                ticket.close(); PENDING.remove(key); FAILED.add(key);
                org.slf4j.LoggerFactory.getLogger(IrisMaterials.class).warn("Cannot prepare alpha mask {}; using the source texture", key, exception);
                return MaterialTexture.ready(original);
            }
            PENDING.remove(key);
            var manager = Minecraft.getInstance().getTextureManager();
            var base = (MaterialTexture)manager.getTexture(original);
            var id = ClientModelManager.id("textures/iris/" + mask.name().toLowerCase(java.util.Locale.ROOT)
                    + "/" + original.getNamespace() + "/" + original.getPath());
            var texture = base.derived(pixels, image -> masked(image, mask));
            try { manager.register(id, texture); }
            catch (RuntimeException | Error exception) { texture.close(); throw exception; }
            TEXTURES.put(key, id);
        }
        return com.unrealtrainmod.client.texture.MaterialTexture.ready(TEXTURES.get(key));
    }

    private static NativeImage masked(NativeImage source, Mask mask) {
        var pixels = new NativeImage(source.getWidth(), source.getHeight(), false);
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
            return pixels;
        } catch (RuntimeException | Error exception) { pixels.close(); throw exception; }
    }


    public static ResourceLocation texture(ResourceLocation original, Mask mask) {
        if (original.equals(ClientModelManager.WHITE)) return original;
        if (FAILED.contains(new Key(original, mask))) return original;
        var value = TEXTURES.get(new Key(original, mask));
        if (value == null) throw new IllegalStateException("Iris material was not prepared: " + original + "/" + mask);
        return value;
    }

    public static void clear() {
        PENDING.values().forEach(com.unrealtrainmod.client.ModelPreparation.Ticket::close); PENDING.clear();
        FAILED.clear();
        var manager = Minecraft.getInstance().getTextureManager();
        TEXTURES.values().forEach(manager::release);
        TEXTURES.clear();
        IrisRenderTypes.clear();
    }
    static void publish() {
        for (var key : java.util.List.copyOf(PENDING.keySet())) if (PENDING.get(key).ready()) ready(key.texture, key.mask);
    }
}
