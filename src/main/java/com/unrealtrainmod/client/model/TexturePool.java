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
import com.unrealtrainmod.client.texture.PbrDiscovery;
import com.unrealtrainmod.client.texture.PbrSource;
import com.unrealtrainmod.client.texture.PreparedTexture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Reload-local pixel sharing. Only canonical images are owned and transferred for upload. */
final class TexturePool implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TexturePool.class);
    record Entry(ResourceLocation id, NativeImage image, TextureAlpha alpha, PbrSource.Files pbr) {}
    private record PixelKey(int width, int height, long hash) {}

    private final Map<PackageAsset, Entry> assets = new HashMap<>();
    private final Map<PixelKey, List<Entry>> pixels = new HashMap<>();
    private final Map<ResourceLocation, PreparedTexture> images = new HashMap<>();
    private final PbrDiscovery pbr = new PbrDiscovery();
    private int atlasPages;

    Entry load(PackageAsset asset) throws IOException {
        var cached = assets.get(asset);
        if (cached != null) return cached;
        var image = TextureLoader.read(asset);
        boolean retained = false;
        try {
            var auxiliary = pbr.find(asset, image.getWidth(), image.getHeight());
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
                if (Objects.equals(auxiliary == null ? null : auxiliary.key(), candidate.pbr() == null ? null : candidate.pbr().key())
                        && samePixels(image, candidate.image())) {
                    assets.put(asset, candidate);
                    return candidate;
                }
            }
            var entry = new Entry(TextureLoader.textureId(asset), image, new TextureAlpha(image, constantMask), auxiliary);
            images.put(entry.id(), new PreparedTexture(image, auxiliary));
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

    NativeImage image(ResourceLocation id) { return images.get(id).image(); }
    PbrSource pbr(ResourceLocation id) { return images.get(id).pbr(); }

    /** Takes ownership of a completed atlas page. */
    ResourceLocation addAtlas(NativeImage image, PbrSource auxiliary) {
        var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "textures/vehicle_atlas/" + atlasPages++);
        images.put(id, new PreparedTexture(image, auxiliary));
        return id;
    }

    Map<ResourceLocation, PreparedTexture> takeImages(Collection<PreparedModel> models) {
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
        long pixelCount = images.values().stream().mapToLong(texture -> (long)texture.image().getWidth() * texture.image().getHeight()).sum();
        pbr.log("vehicles");
        LOGGER.info("Prepared vehicle textures: {} assets -> {} unique source images; {} atlas pages; "
                        + "{} retained for upload, {} pixels ({} RGBA bytes)",
                assets.size(), unique, atlasPages, images.size(), pixelCount, pixelCount * Integer.BYTES);
        var transferred = new HashMap<>(images);
        images.clear();
        return transferred;
    }

    @Override
    public void close() {
        images.values().forEach(PreparedTexture::close);
        images.clear();
    }
}
