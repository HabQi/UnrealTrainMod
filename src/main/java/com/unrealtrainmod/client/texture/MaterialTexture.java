/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.client.GpuResidency;
import com.unrealtrainmod.client.ModelUploads;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
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
    private boolean pending, uploadFailed;
    private int uploadRow, generation;
    private int readers;
    private boolean pixelsClosed;

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
    public final class Pixels implements AutoCloseable {
        public NativeImage image() { return pixels; }
        @Override public void close() {
            readers--;
            if (readers == 0 && pixelsClosed) pixels.close();
        }
    }
    public Pixels retainPixels() { readers++; return new Pixels(); }

    @Override public void load(ResourceManager resources) {}

    /** Model dependencies request storage without forcing getId() to upload the complete image. */
    public static boolean ready(ResourceLocation id) {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
        return !(texture instanceof MaterialTexture material) || material.requestReady();
    }

    public boolean requestReady() {
        GpuResidency.used(this);
        if (material != null) material.used();
        if (uploaded || uploadFailed) return true;
        if (!pending) {
            pending = true;
            int version = generation;
            ModelUploads.enqueue(() -> {
                if (version != generation || !OWNED.contains(this)) return true;
                int rows = Math.min(pixels.getHeight() - uploadRow,
                        Math.max(1, ModelUploads.SLICE_BYTES / (pixels.getWidth() * 4)));
                try {
                    if (!ModelUploads.run(rows * pixels.getWidth() * 4, () -> uploadRows(rows))) return false;
                    if (uploadRow < pixels.getHeight()) return false;
                    ModelUploads.commit(() -> {
                        if (version != generation || !OWNED.contains(this)) return;
                        uploaded = true; pending = false;
                        GpuResidency.uploaded(this, GpuResidency.Kind.BASE_TEXTURE,
                                (long)pixels.getWidth() * pixels.getHeight() * 4, this::releaseId);
                    });
                } catch (RuntimeException exception) {
                    releaseId(); uploadFailed = true;
                    org.slf4j.LoggerFactory.getLogger(MaterialTexture.class).warn("Cannot stage model texture; using standard upload", exception);
                }
                return true;
            });
        }
        return false;
    }

    private void uploadRows(int rows) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            int texture = super.getId();
            if (uploadRow == 0) TextureUtil.prepareImage(texture, pixels.getWidth(), pixels.getHeight());
            RenderSystem.bindTexture(texture);
            pixels.upload(0, 0, uploadRow, 0, uploadRow, pixels.getWidth(), rows, blur, false, false, false);
            uploadRow += rows;
            super.setFilter(blur, false);
        } finally { RenderSystem.bindTexture(previous); }
    }

    @Override public int getId() {
        int texture = super.getId();
        if (!uploaded && !pending) {
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
        uploaded = pending = false;
        uploadRow = 0; generation++;
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
    public static void cancelPending() {
        for (var texture : OWNED) if (texture.pending) texture.releaseId();
    }

    static void invalidatePbr(PbrMaterial material) {
        for (var texture : OWNED) if (texture.material == material) IrisPbrBridge.invalidate(texture.id);
    }

    @Override public void close() {
        if (!OWNED.remove(this)) return;
        try { releaseId(); }
        finally {
            try { if (material != null) material.release(); }
            finally { pixelsClosed = true; if (readers == 0) pixels.close(); }
        }
    }
}
