/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;

/** Transfers initial pixels and an optional lossless reload recipe to the registered texture. */
public record PreparedTexture(NativeImage image, PbrSource pbr, Source source) implements AutoCloseable {
    @FunctionalInterface
    public interface Source {
        NativeImage read() throws IOException;
    }

    public PreparedTexture(NativeImage image, PbrSource pbr) { this(image, pbr, null); }

    static long bytes(NativeImage image) { return (long)image.getWidth() * image.getHeight() * Integer.BYTES; }

    @Override public void close() { image.close(); }
}
