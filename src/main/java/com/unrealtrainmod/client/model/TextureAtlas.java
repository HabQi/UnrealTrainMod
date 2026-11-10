/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.texture.PbrSource;
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
    record Region(ResourceLocation texture, int x, int y, int width, int height, int pageWidth, int pageHeight,
                  double offsetU, double offsetV, double uvScale) {
        float u(float u) {
            float normalized = (float)((u - offsetU) * uvScale);
            return (x + normalized * width) / pageWidth;
        }
        float v(float v) {
            float normalized = (float)((v - offsetV) * uvScale);
            return (y + normalized * height) / pageHeight;
        }
    }

    private record Tile(ResourceLocation texture, NativeImage image, UvBounds uniformUvs) {}
    private record Placement(Tile tile, int x, int y) {}

    /** One affine UV transform per shared image preserves triangle UV directions across every source. */
    private static final class UvBounds {
        private double minU = Double.POSITIVE_INFINITY, minV = Double.POSITIVE_INFINITY;
        private double maxU = Double.NEGATIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;

        void include(RenderBatch batch) {
            int[] indices = batch.mesh().indices();
            float[] vertices = batch.mesh().vertices();
            for (int i = batch.firstIndex(); i < batch.firstIndex() + batch.indexCount(); i++) {
                int vertex = indices[i] * 8;
                float u = vertices[vertex + 3], v = vertices[vertex + 4];
                minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
        }

        boolean unit() { return minU >= 0 && maxU <= 1 && minV >= 0 && maxV <= 1; }
        double scale() { return 1 / Math.max(1, Math.max(maxU - minU, maxV - minV)); }
    }

    private TextureAtlas() {}

    static Map<ResourceLocation, Region> build(Collection<List<ModelPart>> sources, TexturePool textures) {
        var candidates = new HashMap<ResourceLocation, NativeImage>();
        var uniformUvs = new HashMap<ResourceLocation, UvBounds>();
        var excluded = new HashSet<ResourceLocation>();
        for (var parts : sources) for (var part : parts) for (var batch : part.batches()) {
            var id = batch.texture();
            if (id.equals(TextureLoader.WHITE) || excluded.contains(id)) continue;
            var image = textures.image(id);
            boolean eligible = batch.category() == RenderCategory.CUTOUT && image.getWidth() <= MAX_IMAGE_SIZE
                    && image.getHeight() <= MAX_IMAGE_SIZE;
            if (eligible) {
                if (textures.canNormalizeUvs(id)) {
                    uniformUvs.computeIfAbsent(id, ignored -> new UvBounds()).include(batch);
                } else eligible = unitUvs(batch);
            }
            if (!eligible) {
                candidates.remove(id);
                uniformUvs.remove(id);
                excluded.add(id);
            } else {
                candidates.putIfAbsent(id, image);
            }
        }
        var tiles = new ArrayList<Tile>();
        candidates.forEach((id, image) -> tiles.add(new Tile(id, image, uniformUvs.get(id))));
        tiles.sort(Comparator.<Tile>comparingInt(tile -> -tile.image().getHeight())
                .thenComparingInt(tile -> -tile.image().getWidth()).thenComparing(tile -> tile.texture().toString()));
        var regions = new HashMap<ResourceLocation, Region>();
        int configured = ClientConfig.MAX_PACKAGE_TEXTURE_SIZE.get();
        int maximum = configured == 0 ? MAX_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, configured);
        var page = new Page(maximum);
        int pages = 0;
        for (var tile : tiles) {
            if (!page.add(tile)) {
                if (page.emit(textures, regions)) pages++;
                page = new Page(maximum);
                page.add(tile);
            }
        }
        if (page.emit(textures, regions)) pages++;
        int uniformPacked = 0, normalizedUvs = 0;
        for (var tile : tiles) if (tile.uniformUvs() != null && regions.containsKey(tile.texture())) {
            uniformPacked++;
            if (!tile.uniformUvs().unit()) normalizedUvs++;
        }
        LOGGER.info("Vehicle texture atlases: {} eligible textures, {} packed textures, {} pages; "
                        + "uniformPacked={} normalizedUvTextures={}",
                tiles.size(), regions.size(), pages, uniformPacked, normalizedUvs);
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
        private final int maximum;
        private int x, y, rowHeight, width, height;

        Page(int maximum) { this.maximum = maximum; }

        private boolean add(Tile tile) {
            int tileWidth = tile.image().getWidth() + 2, tileHeight = tile.image().getHeight() + 2;
            if (tileWidth > maximum || tileHeight > maximum) return false;
            if (x + tileWidth > maximum) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + tileHeight > maximum) return false;
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
                var auxiliary = new ArrayList<PbrSource.Tile>();
                for (var placement : placements) {
                    var source = textures.pbr(placement.tile().texture());
                    if (source != null) auxiliary.add(new PbrSource.Tile(source, placement.x() + 1, placement.y() + 1));
                }
                var id = textures.addAtlas(atlas, auxiliary.isEmpty() ? null : new PbrSource.Atlas(width, height, auxiliary));
                transferred = true;
                for (var placement : placements) {
                    var tile = placement.tile();
                    var uvs = tile.uniformUvs();
                    boolean normalize = uvs != null && !uvs.unit();
                    regions.put(tile.texture(), new Region(id, placement.x() + 1, placement.y() + 1,
                            tile.image().getWidth(), tile.image().getHeight(), width, height,
                            normalize ? uvs.minU : 0, normalize ? uvs.minV : 0, normalize ? uvs.scale() : 1));
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
