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
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.client.texture.PbrDiscovery;
import com.unrealtrainmod.client.texture.PbrSource;
import com.unrealtrainmod.client.texture.PreparedTexture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Reload-local pixel sharing. Only canonical images are owned and transferred for upload. */
final class TexturePool implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TexturePool.class);
    record Entry(ResourceLocation id, NativeImage image, TextureAlpha alpha, PbrSource pbr) {}
    private record PixelKey(int width, int height, long hash) {}
    private record LightPair(ResourceLocation head, ResourceLocation tail) {}

    private final Map<PackageAsset, Entry> assets = new HashMap<>();
    private final Map<PixelKey, List<Entry>> pixels = new HashMap<>();
    private final Map<LightPair, Entry> lightPairs = new HashMap<>();
    private final Map<ResourceLocation, PreparedTexture> images = new HashMap<>();
    private final PbrDiscovery pbr = new PbrDiscovery();
    private int atlasPages;
    private final java.util.Set<ResourceLocation> flareTextures = new HashSet<>();
    private ResourceLocation defaultFlare;

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
                // PbrDiscovery interns file sources by their complete FileKey within this reload.
                if (auxiliary == candidate.pbr() && samePixels(image, candidate.image())) {
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

    ResourceLocation flare(PackageAsset asset) throws IOException {
        ResourceLocation id;
        if (asset != null) id = load(asset).id();
        else {
            if (defaultFlare == null) {
                var image = new NativeImage(64, 64, false);
                for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                    double dx = (x + 0.5 - 32) / 32, dy = (y + 0.5 - 32) / 32;
                    double radius = Math.sqrt(dx * dx + dy * dy);
                    int alpha = (int)Math.round(255 * Math.pow(Math.max(0, 1 - radius), 3));
                    image.setPixelRGBA(x, y, alpha << 24 | 0xffffff);
                }
                defaultFlare = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "textures/ir/default_flare");
                images.put(defaultFlare, new PreparedTexture(image, null));
            }
            id = defaultFlare;
        }
        flareTextures.add(id);
        return id;
    }

    /** Build the dual-light surface once; the selected RGBA pixel is never blended with the other source. */
    Entry mergeLights(Entry head, Entry tail) {
        if (head == null) return tail;
        if (tail == null || head.id().equals(tail.id())) return head;
        var key = new LightPair(head.id(), tail.id());
        var cached = lightPairs.get(key);
        if (cached != null) return cached;
        var front = head.image(); var back = tail.image();
        int width = Math.max(front.getWidth(), back.getWidth());
        int height = Math.max(front.getHeight(), back.getHeight());
        var image = new NativeImage(width, height, false);
        boolean retained = false;
        try {
            // Only the PBR description needs the one-bit source selection after base-image preparation.
            var tailPixels = head.pbr() == null && tail.pbr() == null ? null
                    : new BitSet(Math.multiplyExact(width, height));
            int[] frontColumns = new int[width], backColumns = new int[width];
            for (int x = 0; x < width; x++) {
                frontColumns[x] = (int)((x + 0.5) * front.getWidth() / width);
                backColumns[x] = (int)((x + 0.5) * back.getWidth() / width);
            }
            int constantMask = TextureAlpha.pixelMask(Math.max(front.getPixelRGBA(0, 0) >>> 24,
                    back.getPixelRGBA(0, 0) >>> 24));
            for (int y = 0; y < height; y++) {
                int frontRow = (int)((y + 0.5) * front.getHeight() / height);
                int backRow = (int)((y + 0.5) * back.getHeight() / height);
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int frontPixel = front.getPixelRGBA(frontColumns[x], frontRow);
                    int backPixel = back.getPixelRGBA(backColumns[x], backRow);
                    boolean useTail = (backPixel >>> 24) > (frontPixel >>> 24);
                    int pixel = useTail ? backPixel : frontPixel;
                    if ((pixel >>> 24) == 0) pixel = 0;
                    image.setPixelRGBA(x, y, pixel);
                    if (useTail && tailPixels != null) tailPixels.set(offset + x);
                    if (constantMask >= 0 && TextureAlpha.pixelMask(pixel >>> 24) != constantMask) constantMask = -1;
                }
            }
            var auxiliary = tailPixels == null ? null
                    : new PbrSource.LightMerge(width, height, head.pbr(), tail.pbr(), tailPixels);
            var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "textures/vehicle_lights/"
                    + ResourceIds.hash("alpha_max_head_tie_v1\0" + head.id() + "\0" + tail.id()));
            var entry = new Entry(id, image, new TextureAlpha(image, constantMask), auxiliary);
            images.put(id, new PreparedTexture(image, auxiliary));
            retained = true;
            lightPairs.put(key, entry);
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
        var used = new HashSet<ResourceLocation>(flareTextures);
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
        LOGGER.info("Prepared vehicle textures: {} assets -> {} unique source images; {} merged light textures; {} atlas pages; "
                        + "{} retained for upload, {} pixels ({} RGBA bytes)",
                assets.size(), unique, lightPairs.size(), atlasPages, images.size(), pixelCount, pixelCount * Integer.BYTES);
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
