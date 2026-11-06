/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import com.unrealtrainmod.resource.PackageAsset;

public record ModelTexture(PackageAsset asset, boolean alphaBlend, Lights lights) {
    public ModelTexture(PackageAsset asset, boolean alphaBlend) { this(asset, alphaBlend, null); }

    /** Missing optional layers remain absent; no fallback to an entirely glowing base image. */
    public record Lights(PackageAsset off, PackageAsset head, PackageAsset tail) {
        public PackageAsset texture(ModelLightLayer layer) {
            return switch (layer) {
                case OFF -> off;
                case HEAD -> head;
                case TAIL -> tail;
                case BASE -> throw new IllegalArgumentException("Base texture is not a light layer");
            };
        }
    }
}
