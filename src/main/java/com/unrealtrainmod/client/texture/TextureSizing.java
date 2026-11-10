/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;

/** Nearest pixel-center sampling keeps alpha thresholds and packed LabPBR values intact. */
public final class TextureSizing {
    private TextureSizing() {}

    /** Takes ownership, retaining the original image when no reduction is needed. */
    public static NativeImage limit(NativeImage image, int maximum) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (maximum == 0 || longest <= maximum) return image;
        try {
            return resize(image, Math.max(1, (int) ((long) image.getWidth() * maximum / longest)),
                    Math.max(1, (int) ((long) image.getHeight() * maximum / longest)));
        } finally {
            image.close();
        }
    }

    /** Returns a new image; the caller still owns the source. */
    public static NativeImage resize(NativeImage source, int width, int height) {
        var result = new NativeImage(width, height, false);
        try {
            int[] columns = new int[width];
            for (int x = 0; x < width; x++) columns[x] = (int) ((x + 0.5) * source.getWidth() / width);
            for (int y = 0; y < height; y++) {
                int row = (int) ((y + 0.5) * source.getHeight() / height);
                for (int x = 0; x < width; x++) result.setPixelRGBA(x, y, source.getPixelRGBA(columns[x], row));
            }
            return result;
        } catch (RuntimeException | Error exception) {
            result.close();
            throw exception;
        }
    }
}
