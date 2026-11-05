/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;

/** Owns base pixels until upload. The optional PBR description owns no pixels or GL resources. */
public record PreparedTexture(NativeImage image, PbrSource pbr) implements AutoCloseable {
    @Override public void close() { image.close(); }
}
