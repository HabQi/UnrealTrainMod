/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.model.TextureLoader;
import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Reload-local descriptions only; decoded auxiliary pixels never remain in a source. */
public sealed interface PbrSource permits PbrSource.Files, PbrSource.Atlas {
    enum Channel {
        NORMAL("_n.png"), SPECULAR("_s.png");
        final String suffix;
        Channel(String suffix) { this.suffix = suffix; }
    }

    boolean has(Channel channel);
    /** Caller owns the result. Null means that Iris should supply its default channel. */
    NativeImage read(Channel channel, int defaultPixel);

    record FileKey(PackageAsset normal, PackageAsset specular, int width, int height) {}

    final class Files implements PbrSource {
        private final FileKey key;
        private final EnumSet<Channel> failed = EnumSet.noneOf(Channel.class);

        Files(FileKey key) { this.key = key; }
        public FileKey key() { return key; }
        private PackageAsset asset(Channel channel) { return channel == Channel.NORMAL ? key.normal() : key.specular(); }
        @Override public boolean has(Channel channel) { return asset(channel) != null; }

        @Override public NativeImage read(Channel channel, int defaultPixel) {
            var asset = asset(channel);
            if (asset == null || failed.contains(channel)) return null;
            NativeImage image = null;
            try {
                image = TextureLoader.read(asset);
                if (image.getWidth() != key.width() || image.getHeight() != key.height()) {
                    throw new IOException("Expected " + key.width() + "x" + key.height()
                            + ", got " + image.getWidth() + "x" + image.getHeight());
                }
                PbrStats.decoded();
                return image;
            } catch (IOException | RuntimeException exception) {
                if (image != null) image.close();
                failed.add(channel);
                PbrStats.failure();
                LoggerFactory.getLogger(PbrSource.class).warn("Ignoring PBR texture {}: {}", asset, exception.getMessage());
                return null;
            }
        }
    }

    /** x/y are content coordinates, matching the base atlas, including its repeating border. */
    record Tile(PbrSource source, int x, int y) {}

    record Atlas(int width, int height, List<Tile> tiles) implements PbrSource {
        public Atlas { tiles = List.copyOf(tiles); }
        @Override public boolean has(Channel channel) { return tiles.stream().anyMatch(tile -> tile.source().has(channel)); }

        @Override public NativeImage read(Channel channel, int defaultPixel) {
            NativeImage atlas = null;
            try {
                for (var tile : tiles) {
                    try (var image = tile.source().read(channel, defaultPixel)) {
                        if (image == null) continue;
                        if (atlas == null) {
                            atlas = new NativeImage(width, height, false);
                            atlas.fillRect(0, 0, width, height, defaultPixel);
                        }
                        int w = image.getWidth(), h = image.getHeight();
                        for (int y = -1; y <= h; y++) for (int x = -1; x <= w; x++) {
                            atlas.setPixelRGBA(tile.x() + x, tile.y() + y,
                                    image.getPixelRGBA((x + w) % w, (y + h) % h));
                        }
                    }
                }
                return atlas;
            } catch (RuntimeException | Error exception) {
                if (atlas != null) atlas.close();
                throw exception;
            }
        }
    }
}
