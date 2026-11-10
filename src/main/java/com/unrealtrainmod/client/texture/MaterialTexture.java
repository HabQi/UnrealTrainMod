/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.client.GpuResidency;
import com.unrealtrainmod.client.compat.iris.IrisPbrBridge;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;

/** CPU pixels survive GPU eviction; base and alpha-derived textures share auxiliary channels. */
public final class MaterialTexture extends AbstractTexture {
    private static final Set<MaterialTexture> OWNED = Collections.newSetFromMap(new IdentityHashMap<>());
    private final PbrMaterial material;
    private final NativeImage pixels;
    private boolean uploaded;

    public MaterialTexture(PreparedTexture prepared) {
        this(prepared.image(), prepared.pbr() == null ? null : new PbrMaterial(prepared.pbr()));
    }

    public MaterialTexture(NativeImage pixels) { this(pixels, null); }

    private MaterialTexture(NativeImage pixels, PbrMaterial material) {
        this.pixels = pixels;
        this.material = material;
        OWNED.add(this);
        if (material != null) material.retain();
    }

    public NativeImage getPixels() { return pixels; }

    @Override public void load(ResourceManager resources) {}

    @Override public int getId() {
        int texture = super.getId();
        if (!uploaded) {
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                TextureUtil.prepareImage(texture, pixels.getWidth(), pixels.getHeight());
                RenderSystem.bindTexture(texture);
                pixels.upload(0, 0, 0, false);
                uploaded = true;
                super.setFilter(blur, false);
                GpuResidency.uploaded(this, GpuResidency.Kind.BASE_TEXTURE,
                        (long) pixels.getWidth() * pixels.getHeight() * 4, this::releaseId);
            } catch (RuntimeException | Error exception) {
                releaseId();
                throw exception;
            } finally {
                RenderSystem.bindTexture(previous);
            }
        }
        GpuResidency.used(this);
        if (material != null) material.used();
        return texture;
    }

    @Override public void releaseId() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::releaseId);
            return;
        }
        IrisPbrBridge.invalidate(id);
        GpuResidency.released(this);
        uploaded = false;
        super.releaseId();
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
        try { releaseId(); }
        finally {
            try { if (material != null) material.release(); }
            finally { pixels.close(); }
        }
    }
}
