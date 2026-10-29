/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Reload-local pixel sharing. Only canonical images are owned and transferred for upload. */
final class TexturePool implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TexturePool.class);
    record Entry(ResourceLocation id, NativeImage image, TextureAlpha alpha) {}
    private record PixelKey(int width, int height, long hash) {}

    private final Map<PackageAsset, Entry> assets = new HashMap<>();
    private final Map<PixelKey, List<Entry>> pixels = new HashMap<>();
    private final Map<ResourceLocation, NativeImage> images = new HashMap<>();
    private int atlasPages;

    Entry load(PackageAsset asset) throws IOException {
        var cached = assets.get(asset);
        if (cached != null) return cached;
        var image = TextureLoader.read(asset);
        boolean retained = false;
        try {
            long hash = 0xcbf29ce484222325L;
            int constantMask = TextureAlpha.pixelMask(image.getPixelRGBA(0, 0) >>> 24);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    hash = (hash ^ Integer.toUnsignedLong(pixel)) * 0x100000001b3L;
                    if (constantMask >= 0 && TextureAlpha.pixelMask(pixel >>> 24) != constantMask) constantMask = -1;
                }
            }
            var key = new PixelKey(image.getWidth(), image.getHeight(), hash);
            var candidates = pixels.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (var candidate : candidates) {
                if (samePixels(image, candidate.image())) {
                    assets.put(asset, candidate);
                    return candidate;
                }
            }
            var entry = new Entry(TextureLoader.textureId(asset), image, new TextureAlpha(image, constantMask));
            images.put(entry.id(), image);
            retained = true;
            candidates.add(entry);
            assets.put(asset, entry);
            return entry;
        } finally {
            if (!retained) image.close();
        }
    }

    private static boolean samePixels(NativeImage first, NativeImage second) {
        // Width and height already matched through PixelKey; a hash alone never proves equality.
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getPixelRGBA(x, y) != second.getPixelRGBA(x, y)) return false;
            }
        }
        return true;
    }

    NativeImage image(ResourceLocation id) { return images.get(id); }

    /** Takes ownership of a completed atlas page. */
    ResourceLocation addAtlas(NativeImage image) {
        var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "textures/vehicle_atlas/" + atlasPages++);
        images.put(id, image);
        return id;
    }

    Map<ResourceLocation, NativeImage> takeImages(Collection<PreparedModel> models) {
        int unique = pixels.values().stream().mapToInt(List::size).sum();
        var used = new HashSet<ResourceLocation>();
        for (var model : models) for (var batch : model.batches()) used.add(batch.texture());
        var iterator = images.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            // Include hidden-part batches too. Forced OPAQUE can still require an alpha-zero image.
            if (!used.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
        long pixelCount = images.values().stream().mapToLong(image -> (long)image.getWidth() * image.getHeight()).sum();
        LOGGER.info("Prepared vehicle textures: {} assets -> {} unique source images; {} atlas pages; "
                        + "{} retained for upload, {} pixels ({} RGBA bytes)",
                assets.size(), unique, atlasPages, images.size(), pixelCount, pixelCount * Integer.BYTES);
        var transferred = new HashMap<>(images);
        images.clear();
        return transferred;
    }

    @Override
    public void close() {
        images.values().forEach(NativeImage::close);
        images.clear();
    }
}
