/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import com.unrealtrainmod.resource.PackageAsset;
import java.util.Map;

/** Geometry reference and import/render options, independent of vehicle catalogs. */
public record ModelSource(PackageAsset model, Map<String, ModelTexture> textures,
                          Map<String, MaterialRenderMode> materialRenderModes, boolean smoothing, boolean cullFaces,
                          Format format, float scale) {
    public static final float MQO_SCALE = 0.01f;
    public enum Format { MQO, IR_OBJ }

    public ModelSource(PackageAsset model, Map<String, ModelTexture> textures,
                       Map<String, MaterialRenderMode> materialRenderModes, boolean smoothing, boolean cullFaces) {
        this(model, textures, materialRenderModes, smoothing, cullFaces, Format.MQO, MQO_SCALE);
    }

    public ModelSource {
        textures = Map.copyOf(textures);
        materialRenderModes = Map.copyOf(materialRenderModes);
    }
}
