/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.UnrealTrainMod;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.minecraft.resources.ResourceLocation;

/** Vehicle image decoding and stable texture identifiers; callers own returned pixels. */
public final class TextureLoader {
    public static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(
            UnrealTrainMod.MODID, "textures/model/white.png");

    private TextureLoader() {}

    public static NativeImage read(PackageAsset asset) throws IOException {
        try {
            byte[] bytes;
            try (var input = asset.open()) {
                bytes = input.readAllBytes();
            }
            if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff) {
                return readJpeg(bytes);
            }
            // The byte[] overload copies the whole encoded image onto LWJGL's limited MemoryStack.
            return NativeImage.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            throw new IOException("Unable to decode vehicle texture " + asset, e);
        }
    }

    private static NativeImage readJpeg(byte[] bytes) throws IOException {
        BufferedImage decoded;
        // An explicit memory cache avoids ImageIO's process-wide disk-cache setting.
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("No JPEG image reader available");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        var image = new NativeImage(NativeImage.Format.RGBA, decoded.getWidth(), decoded.getHeight(), false);
        try {
            for (int y = 0; y < decoded.getHeight(); y++) {
                for (int x = 0; x < decoded.getWidth(); x++) {
                    int argb = decoded.getRGB(x, y);
                    // NativeImage's RGBA pixel integer is ABGR; JPEG contributes no alpha channel.
                    int abgr = 0xff000000 | ((argb & 255) << 16) | (argb & 0xff00) | ((argb >>> 16) & 255);
                    image.setPixelRGBA(x, y, abgr);
                }
            }
            return image;
        } catch (RuntimeException | Error e) {
            image.close();
            throw e;
        } finally {
            decoded.flush();
        }
    }

    public static ResourceLocation textureId(PackageAsset asset) {
        return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "textures/package/"
                + ResourceIds.hash(asset.archive().toAbsolutePath().normalize() + "\0" + asset.entry()));
    }
}
