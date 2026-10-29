/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import net.minecraft.resources.ResourceLocation;

/** A borrowed texture. Positive pixelScale fixes GUI units per texel; zero fills the row. */
public record ListEntryImage(ResourceLocation id, int width, int height, float pixelScale) {
    public ListEntryImage(ResourceLocation id, int width, int height) { this(id, width, height, 0); }
}
