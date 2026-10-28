/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.model.MaterialRenderMode;

/** Conservative alpha queries for nearest-filtered, repeating textures. Exists only during preparation. */
final class TextureAlpha {
    private static final int PARTIAL = 1, OPAQUE = 2;
    static final TextureAlpha WHITE = new TextureAlpha(OPAQUE);
    private final int width, height, stride;
    private final int constantMask;
    private final int[] partial, opaque;

    private TextureAlpha(int mask) {
        width = height = stride = 0;
        constantMask = mask;
        partial = opaque = null;
    }

    /** A nonnegative mask means every texel has that alpha class; -1 requests spatial queries. */
    TextureAlpha(NativeImage image, int constantMask) {
        width = image.getWidth();
        height = image.getHeight();
        stride = Math.addExact(width, 1);
        // Only images with different alpha classes need spatial queries.
        this.constantMask = constantMask;
        if (constantMask >= 0) {
            partial = opaque = null;
            return;
        }
        int size = Math.multiplyExact(stride, Math.addExact(height, 1));
        partial = new int[size];
        opaque = new int[size];
        for (int y = 0; y < height; y++) {
            int partialRow = 0, opaqueRow = 0;
            for (int x = 0; x < width; x++) {
                int alpha = image.getPixelRGBA(x, y) >>> 24;
                if (alpha >= 26 && alpha < 255) partialRow++;
                if (alpha == 255) opaqueRow++;
                int i = (y + 1) * stride + x + 1;
                partial[i] = partial[i - stride] + partialRow;
                opaque[i] = opaque[i - stride] + opaqueRow;
            }
        }
    }

    static int pixelMask(int alpha) {
        return alpha == 255 ? OPAQUE : alpha >= 26 ? PARTIAL : 0;
    }

    /** Null means that no fragment can contribute color. Vertex alpha already includes material alpha. */
    RenderCategory classify(float[] vertices, int[] colors, int a, int b, int c,
                            boolean alphaBlend, MaterialRenderMode mode) {
        // Forced opaque geometry must survive even entirely transparent textures and vertex colors.
        if (mode == MaterialRenderMode.OPAQUE) return RenderCategory.CUTOUT;
        int minAlpha = Math.min(colors[a] >>> 24, Math.min(colors[b] >>> 24, colors[c] >>> 24));
        int maxAlpha = Math.max(colors[a] >>> 24, Math.max(colors[b] >>> 24, colors[c] >>> 24));
        if (maxAlpha == 0) return null;
        if (mode == null && !alphaBlend && minAlpha == 255) return RenderCategory.CUTOUT;
        int mask = constantMask;
        if (mask < 0) {
            float minU = Math.min(vertices[a * 8 + 3], Math.min(vertices[b * 8 + 3], vertices[c * 8 + 3]));
            float maxU = Math.max(vertices[a * 8 + 3], Math.max(vertices[b * 8 + 3], vertices[c * 8 + 3]));
            float minV = Math.min(vertices[a * 8 + 4], Math.min(vertices[b * 8 + 4], vertices[c * 8 + 4]));
            float maxV = Math.max(vertices[a * 8 + 4], Math.max(vertices[b * 8 + 4], vertices[c * 8 + 4]));
            mask = mask(minU, minV, maxU, maxV);
        }
        if (mask == 0) return null;
        // CUTOUT remains conservative: the shader evaluates the interpolated final alpha.
        if (mode == MaterialRenderMode.CUTOUT) return RenderCategory.CUTOUT;
        if (mode == MaterialRenderMode.TRANSLUCENT) return RenderCategory.BLEND;
        boolean blend = (mask & PARTIAL) != 0 || minAlpha < 255;
        boolean solid = (mask & OPAQUE) != 0 && maxAlpha == 255;
        return blend ? (solid ? RenderCategory.MIXED : RenderCategory.BLEND) : RenderCategory.CUTOUT;
    }

    private int mask(float minU, float minV, float maxU, float maxV) {
        // Inclusive texel bounds, expanded by one texel on each side. Long/double arithmetic avoids UV overflow.
        double x0 = Math.floor((double)minU * width) - 1;
        double y0 = Math.floor((double)minV * height) - 1;
        double xLength = Math.floor((double)maxU * width) + 2 - x0;
        double yLength = Math.floor((double)maxV * height) + 2 - y0;
        // At extreme UV magnitudes a double cannot resolve individual texels. Query the full axis instead.
        if (Math.max(Math.abs((double)minU * width), Math.abs((double)maxU * width)) >= 0x1p50) xLength = width;
        if (Math.max(Math.abs((double)minV * height), Math.abs((double)maxV * height)) >= 0x1p50) yLength = height;
        int nx = (int)Math.min(width, xLength), ny = (int)Math.min(height, yLength);
        int x = nx == width ? 0 : wrapped(x0, width);
        int y = ny == height ? 0 : wrapped(y0, height);
        int xEnd = Math.min(width, x + nx), yEnd = Math.min(height, y + ny);
        int result = rectangle(x, y, xEnd, yEnd);
        int wrapX = nx - (xEnd - x), wrapY = ny - (yEnd - y);
        if (wrapX > 0) result |= rectangle(0, y, wrapX, yEnd);
        if (wrapY > 0) result |= rectangle(x, 0, xEnd, wrapY);
        if (wrapX > 0 && wrapY > 0) result |= rectangle(0, 0, wrapX, wrapY);
        return result;
    }

    private static int wrapped(double texel, int size) {
        double remainder = texel % size;
        return (int)(remainder < 0 ? remainder + size : remainder);
    }

    private int rectangle(int x0, int y0, int x1, int y1) {
        int a = y0 * stride + x0, b = y0 * stride + x1;
        int c = y1 * stride + x0, d = y1 * stride + x1;
        return (partial[d] - partial[b] - partial[c] + partial[a] > 0 ? PARTIAL : 0)
                | (opaque[d] - opaque[b] - opaque[c] + opaque[a] > 0 ? OPAQUE : 0);
    }
}
