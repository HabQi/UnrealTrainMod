/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.model.TextureLoader;
import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Reload-local descriptions only; decoded auxiliary pixels never remain in a source. */
public sealed interface PbrSource permits PbrSource.Files, PbrSource.Resources, PbrSource.Atlas, PbrSource.LightMerge {
    enum Channel {
        NORMAL("_n.png"), SPECULAR("_s.png");
        final String suffix;
        Channel(String suffix) { this.suffix = suffix; }
    }

    boolean has(Channel channel);
    /** Caller owns the result. Null means that Iris should supply its default channel. */
    NativeImage read(Channel channel, int defaultPixel);

    record FileKey(PackageAsset normal, PackageAsset specular, int width, int height) {}

    /** Pack resources remain lazy, just like ZIP auxiliary channels; base pixels have separate ownership. */
    final class Resources implements PbrSource {
        private final net.minecraft.server.packs.resources.Resource normal, specular;
        private final int width, height;
        private final EnumSet<Channel> failed = EnumSet.noneOf(Channel.class);
        public Resources(net.minecraft.server.packs.resources.Resource normal,
                         net.minecraft.server.packs.resources.Resource specular, int width, int height) {
            this.normal = normal; this.specular = specular; this.width = width; this.height = height;
        }
        private net.minecraft.server.packs.resources.Resource resource(Channel channel) {
            return channel == Channel.NORMAL ? normal : specular;
        }
        @Override public boolean has(Channel channel) { return resource(channel) != null; }
        @Override public NativeImage read(Channel channel, int defaultPixel) {
            var resource = resource(channel);
            if (resource == null || failed.contains(channel)) return null;
            NativeImage image = null;
            try (var input = resource.open()) {
                image = NativeImage.read(input);
                if (image.getWidth() != width || image.getHeight() != height) {
                    var resized = TextureSizing.resize(image, width, height);
                    image.close(); image = resized;
                }
                PbrStats.decoded();
                return image;
            } catch (IOException | RuntimeException exception) {
                if (image != null) image.close();
                failed.add(channel); PbrStats.failure();
                LoggerFactory.getLogger(PbrSource.class).warn("Ignoring resource PBR channel {}: {}", channel, exception.getMessage());
                return null;
            } catch (Error error) { if (image != null) image.close(); throw error; }
        }
    }

    final class Files implements PbrSource {
        private final FileKey key;
        private final EnumSet<Channel> failed = EnumSet.noneOf(Channel.class);

        Files(FileKey key) { this.key = key; }
        private PackageAsset asset(Channel channel) { return channel == Channel.NORMAL ? key.normal() : key.specular(); }
        @Override public boolean has(Channel channel) { return asset(channel) != null; }

        @Override public NativeImage read(Channel channel, int defaultPixel) {
            var asset = asset(channel);
            if (asset == null || failed.contains(channel)) return null;
            NativeImage image = null;
            try {
                image = TextureLoader.read(asset);
                if (image.getWidth() != key.width() || image.getHeight() != key.height()) {
                    int width = image.getWidth(), height = image.getHeight();
                    var resized = TextureSizing.resize(image, key.width(), key.height());
                    image.close();
                    image = resized;
                    LoggerFactory.getLogger(PbrSource.class).info("Resized PBR texture {}: {}x{} -> {}x{} (nearest sampling)",
                            asset, width, height, key.width(), key.height());
                }
                PbrStats.decoded();
                return image;
            } catch (IOException | RuntimeException exception) {
                if (image != null) image.close();
                failed.add(channel);
                PbrStats.failure();
                LoggerFactory.getLogger(PbrSource.class).warn("Ignoring PBR texture {}: {}", asset, exception.getMessage());
                return null;
            } catch (Error error) {
                if (image != null) image.close();
                throw error;
            }
        }
    }

    /** Retains only source descriptions and the base merge's immutable pixel-selection mask. */
    record LightMerge(int width, int height, PbrSource head, PbrSource tail, BitSet tailPixels) implements PbrSource {
        @Override public boolean has(Channel channel) {
            return (head != null && head.has(channel)) || (tail != null && tail.has(channel));
        }

        @Override public NativeImage read(Channel channel, int defaultPixel) {
            NativeImage merged = null;
            try (var front = readAligned(head, channel, defaultPixel);
                 var back = readAligned(tail, channel, defaultPixel)) {
                if (front == null && back == null) return null;
                merged = new NativeImage(width, height, false);
                for (int y = 0; y < height; y++) {
                    int offset = y * width;
                    for (int x = 0; x < width; x++) {
                        var source = tailPixels.get(offset + x) ? back : front;
                        merged.setPixelRGBA(x, y, source == null ? defaultPixel : source.getPixelRGBA(x, y));
                    }
                }
                return merged;
            } catch (RuntimeException | Error exception) {
                if (merged != null) merged.close();
                throw exception;
            }
        }

        private NativeImage readAligned(PbrSource source, Channel channel, int defaultPixel) {
            var image = source == null ? null : source.read(channel, defaultPixel);
            if (image == null || (image.getWidth() == width && image.getHeight() == height)) return image;
            try {
                return TextureSizing.resize(image, width, height);
            } finally {
                image.close();
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
