/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.model.TextureLoader;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared RTM selection artwork, cropped independently of model geometry and textures. */
public final class ClientButtonTextureManager extends SimplePreparableReloadListener<Map<PackageAsset, NativeImage>> {
    public static final ClientButtonTextureManager INSTANCE = new ClientButtonTextureManager();
    public static final int WIDTH = 320;
    public static final int HEIGHT = 64;
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientButtonTextureManager.class);
    private final Map<PackageAsset, ResourceLocation> textures = new HashMap<>();

    private ClientButtonTextureManager() {}

    public ResourceLocation texture(PackageAsset asset) { return textures.get(asset); }

    @Override
    protected Map<PackageAsset, NativeImage> prepare(ResourceManager resources, ProfilerFiller profiler) {
        var assets = new HashSet<PackageAsset>();
        VehicleCatalog.all().forEach(model -> assets.add(model.buttonTexture()));
        RailCatalog.all().forEach(model -> assets.add(model.buttonTexture()));
        assets.remove(null);
        var images = new HashMap<PackageAsset, NativeImage>();
        try {
            for (var asset : assets) {
                try {
                    images.put(asset, crop(asset));
                } catch (IOException | RuntimeException exception) {
                    LOGGER.warn("Unable to load RTM button {}; using text entry: {}", asset, exception.getMessage());
                }
            }
            return images;
        } catch (RuntimeException | Error exception) {
            images.values().forEach(NativeImage::close);
            throw exception;
        }
    }

    private static NativeImage crop(PackageAsset asset) throws IOException {
        try (var source = TextureLoader.read(asset)) {
            if (source.getWidth() < WIDTH || source.getHeight() < HEIGHT) {
                throw new IOException("buttonTexture must contain the fixed top-left 320x64 region");
            }
            var cropped = new NativeImage(WIDTH, HEIGHT, false);
            try {
                boolean visible = false;
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        int pixel = source.getPixelRGBA(x, y);
                        visible |= (pixel >>> 24) != 0;
                        cropped.setPixelRGBA(x, y, pixel);
                    }
                }
                if (!visible) throw new IOException("buttonTexture has a fully transparent 320x64 region");
                return cropped;
            } catch (IOException | RuntimeException | Error exception) {
                cropped.close();
                throw exception;
            }
        }
    }

    @Override
    protected void apply(Map<PackageAsset, NativeImage> prepared, ResourceManager resources, ProfilerFiller profiler) {
        RenderSystem.assertOnRenderThread();
        var pending = new HashMap<>(prepared);
        try {
            close();
            for (var asset : prepared.keySet()) {
                var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID,
                        "button/" + ResourceIds.hash(asset.toString()));
                var image = pending.remove(asset);
                try {
                    upload(id, image);
                    textures.put(asset, id);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Unable to upload RTM button {}; using text entry: {}", asset, exception.getMessage());
                }
            }
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        } finally {
            pending.values().forEach(NativeImage::close);
        }
    }

    /** Takes ownership of the image, including when upload fails. */
    private static void upload(ResourceLocation id, NativeImage image) {
        DynamicTexture texture;
        try {
            texture = new DynamicTexture(image);
        } catch (RuntimeException | Error exception) {
            image.close();
            throw exception;
        }
        try {
            Minecraft.getInstance().getTextureManager().register(id, texture);
        } catch (RuntimeException | Error exception) {
            texture.close();
            throw exception;
        }
    }

    public void close() {
        RenderSystem.assertOnRenderThread();
        var manager = Minecraft.getInstance().getTextureManager();
        textures.values().forEach(manager::release);
        textures.clear();
    }
}
