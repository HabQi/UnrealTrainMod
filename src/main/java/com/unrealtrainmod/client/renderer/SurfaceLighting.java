/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

/** Per-submission surface lighting; never part of the shared geometry or texture cache. */
public record SurfaceLighting(float emission, float fill) {
    public static final SurfaceLighting NONE = new SurfaceLighting(0, 0);

    public int light(int packed) {
        int block = Math.max(packed & 0xffff, Math.round(240 * Math.max(fill, emission)));
        int sky = Math.max(packed >>> 16 & 0xffff, Math.round(240 * emission));
        return block | sky << 16;
    }
}
