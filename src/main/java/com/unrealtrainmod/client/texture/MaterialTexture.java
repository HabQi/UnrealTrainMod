/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.compat.iris.IrisPbrBridge;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Retains base pixels for alpha masks; derived textures share demand-loaded auxiliary channels. */
public final class MaterialTexture extends DynamicTexture {
    private static final Set<MaterialTexture> OWNED = Collections.newSetFromMap(new IdentityHashMap<>());
    private final PbrMaterial material;

    public MaterialTexture(PreparedTexture prepared) {
        this(prepared.image(), prepared.pbr() == null ? null : new PbrMaterial(prepared.pbr()));
    }

    private MaterialTexture(NativeImage pixels, PbrMaterial material) {
        super(pixels);
        this.material = material;
        // DynamicTexture uploads in super(), before this source is assigned. Discard any premature default holder.
        try {
            IrisPbrBridge.invalidate(id);
            OWNED.add(this);
            if (material != null) material.retain();
        } catch (RuntimeException | Error exception) { super.close(); throw exception; }
    }

    public MaterialTexture derived(NativeImage pixels) { return new MaterialTexture(pixels, material); }

    public AbstractTexture auxiliary(PbrSource.Channel channel, int defaultPixel) {
        return material == null ? null : material.acquire(channel, defaultPixel, blur);
    }

    public static void invalidatePbr() {
        for (var texture : OWNED) IrisPbrBridge.invalidate(texture.id);
    }

    public static void releasePbr() {
        invalidatePbr();
        var materials = Collections.newSetFromMap(new IdentityHashMap<PbrMaterial, Boolean>());
        for (var texture : OWNED) if (texture.material != null) materials.add(texture.material);
        materials.forEach(PbrMaterial::clear);
    }

    static void invalidatePbr(PbrMaterial material) {
        for (var texture : OWNED) if (texture.material == material) IrisPbrBridge.invalidate(texture.id);
    }

    @Override public void close() {
        if (!OWNED.remove(this)) return;
        try { IrisPbrBridge.invalidate(id); }
        finally {
            try { if (material != null) material.release(); }
            finally { super.close(); }
        }
    }
}
