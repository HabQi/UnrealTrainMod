/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.renderer.RenderBatch;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Reload-wide, unscaled small textures. Alpha classification always happens before atlas planning. */
final class TextureAtlas {
    private static final Logger LOGGER = LoggerFactory.getLogger(TextureAtlas.class);
    private static final int MAX_IMAGE_SIZE = 512, MAX_PAGE_SIZE = 2048;

    /** x/y point to the first content texel, after the one-texel repeating border. */
    record Region(ResourceLocation texture, int x, int y, int width, int height, int pageWidth, int pageHeight) {
        float u(float u) { return (x + u * width) / pageWidth; }
        float v(float v) { return (y + v * height) / pageHeight; }
    }

    private record Tile(ResourceLocation texture, NativeImage image) {}
    private record Placement(Tile tile, int x, int y) {}

    private TextureAtlas() {}

    static Map<ResourceLocation, Region> build(Collection<List<ModelPart>> sources, TexturePool textures) {
        var candidates = new HashMap<ResourceLocation, NativeImage>();
        var excluded = new HashSet<ResourceLocation>();
        for (var parts : sources) for (var part : parts) for (var batch : part.batches()) {
            var id = batch.texture();
            if (id.equals(TextureLoader.WHITE) || excluded.contains(id)) continue;
            var image = textures.image(id);
            if (batch.category() != RenderCategory.CUTOUT || image.getWidth() > MAX_IMAGE_SIZE
                    || image.getHeight() > MAX_IMAGE_SIZE || !unitUvs(batch)) {
                candidates.remove(id);
                excluded.add(id);
            } else {
                candidates.putIfAbsent(id, image);
            }
        }
        var tiles = new ArrayList<Tile>();
        candidates.forEach((id, image) -> tiles.add(new Tile(id, image)));
        tiles.sort(Comparator.<Tile>comparingInt(tile -> -tile.image().getHeight())
                .thenComparingInt(tile -> -tile.image().getWidth()).thenComparing(tile -> tile.texture().toString()));
        var regions = new HashMap<ResourceLocation, Region>();
        var page = new Page();
        int pages = 0;
        for (var tile : tiles) {
            if (!page.add(tile)) {
                if (page.emit(textures, regions)) pages++;
                page = new Page();
                page.add(tile);
            }
        }
        if (page.emit(textures, regions)) pages++;
        LOGGER.info("Vehicle texture atlases: {} eligible textures, {} packed textures, {} pages",
                tiles.size(), regions.size(), pages);
        return Map.copyOf(regions);
    }

    private static boolean unitUvs(RenderBatch batch) {
        int[] indices = batch.mesh().indices();
        float[] vertices = batch.mesh().vertices();
        for (int i = batch.firstIndex(); i < batch.firstIndex() + batch.indexCount(); i++) {
            int vertex = indices[i] * 8;
            float u = vertices[vertex + 3], v = vertices[vertex + 4];
            if (!(u >= 0 && u <= 1 && v >= 0 && v <= 1)) return false;
        }
        return true;
    }

    private static final class Page {
        private final List<Placement> placements = new ArrayList<>();
        private int x, y, rowHeight, width, height;

        private boolean add(Tile tile) {
            int tileWidth = tile.image().getWidth() + 2, tileHeight = tile.image().getHeight() + 2;
            if (x + tileWidth > MAX_PAGE_SIZE) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + tileHeight > MAX_PAGE_SIZE) return false;
            placements.add(new Placement(tile, x, y));
            x += tileWidth;
            rowHeight = Math.max(rowHeight, tileHeight);
            width = Math.max(width, x);
            height = Math.max(height, y + tileHeight);
            return true;
        }

        private boolean emit(TexturePool textures, Map<ResourceLocation, Region> regions) {
            if (placements.size() < 2) return false;
            var atlas = new NativeImage(width, height, true);
            boolean transferred = false;
            try {
                for (var placement : placements) {
                    var image = placement.tile().image();
                    int w = image.getWidth(), h = image.getHeight();
                    // Repeat, rather than clamp, so UV=1 samples the authored first texel too.
                    for (int row = -1; row <= h; row++) {
                        int sourceY = (row + h) % h;
                        for (int column = -1; column <= w; column++) {
                            int sourceX = (column + w) % w;
                            atlas.setPixelRGBA(placement.x() + column + 1, placement.y() + row + 1,
                                    image.getPixelRGBA(sourceX, sourceY));
                        }
                    }
                }
                var id = textures.addAtlas(atlas);
                transferred = true;
                for (var placement : placements) {
                    var tile = placement.tile();
                    regions.put(tile.texture(), new Region(id, placement.x() + 1, placement.y() + 1,
                            tile.image().getWidth(), tile.image().getHeight(), width, height));
                }
                LOGGER.info("Vehicle texture atlas {}: {}x{}, {} source images, {} RGBA bytes",
                        id, width, height, placements.size(), (long)width * height * Integer.BYTES);
                return true;
            } finally {
                if (!transferred) atlas.close();
            }
        }
    }
}
