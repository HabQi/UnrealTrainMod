/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.client.GpuResidency;
import com.unrealtrainmod.client.ModelPreparation;
import com.unrealtrainmod.client.ModelUploads;
import com.unrealtrainmod.client.compat.iris.IrisPbrBridge;
import com.unrealtrainmod.client.model.TextureLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.slf4j.LoggerFactory;

/** Owns a GPU texture independently of its decoded pixels. Alpha variants share auxiliary channels. */
public final class MaterialTexture extends AbstractTexture {
    private static final Set<MaterialTexture> OWNED = Collections.newSetFromMap(new IdentityHashMap<>());
    private final PbrMaterial material;
    private final PreparedTexture.Source source;
    private NativeImage pixels;
    private ModelPreparation.Ticket<NativeImage> decode;
    private long pixelsUsedFrame = Long.MIN_VALUE;
    private boolean uploaded;
    private boolean pending, uploadFailed;
    private int uploadRow, generation;
    private int readers;

    public MaterialTexture(PreparedTexture prepared) {
        this(prepared.image(), prepared.pbr() == null ? null : new PbrMaterial(prepared.pbr()), prepared.source());
    }

    public MaterialTexture(NativeImage pixels) { this(pixels, null, null); }

    private MaterialTexture(NativeImage pixels, PbrMaterial material, PreparedTexture.Source source) {
        this.material = material;
        this.source = source;
        OWNED.add(this);
        if (material != null) material.retain();
        installPixels(pixels);
    }

    public final class Pixels implements AutoCloseable {
        public NativeImage image() { return pixels; }
        @Override public void close() {
            readers--;
            if (readers == 0 && !OWNED.contains(MaterialTexture.this)) releasePixels();
        }
    }
    public Pixels retainPixels() {
        if (!requestPixels()) return null;
        readers++;
        return new Pixels();
    }

    long pixelBytes() { return PreparedTexture.bytes(pixels); }
    boolean pixelsIdle() { return readers == 0 && !pending && pixelsUsedFrame < ModelUploads.frame() - 1; }

    void discardPixels() {
        pixels.close();
        pixels = null;
    }

    private void releasePixels() {
        if (pixels == null) return;
        TexturePixelCache.released(this);
        discardPixels();
    }

    private void installPixels(NativeImage image) {
        pixels = image;
        if (source != null) TexturePixelCache.loaded(this, pixelBytes());
    }

    private void usePixels() {
        if (source == null) return;
        if (pixelsUsedFrame != ModelUploads.frame()) {
            pixelsUsedFrame = ModelUploads.frame();
            TexturePixelCache.used(this);
        }
    }

    private boolean requestPixels() {
        if (pixels != null) {
            usePixels();
            return true;
        }
        if (decode == null) {
            decode = ModelPreparation.geometry(() -> readPixels(source), PreparedTexture::bytes, NativeImage::close);
            if (decode != null) decode.whenReady(this::publishPixels);
        }
        return false;
    }

    private static NativeImage readPixels(PreparedTexture.Source source) {
        try { return source.read(); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private void restorePixels(Supplier<NativeImage> read) {
        NativeImage image;
        try {
            image = read.get();
            TexturePixelCache.restored(PreparedTexture.bytes(image));
        } catch (RuntimeException exception) {
            TexturePixelCache.failed();
            LoggerFactory.getLogger(MaterialTexture.class).warn("Cannot restore decoded vehicle texture; using missing texture", exception);
            image = readPixels(TextureLoader::readMissing);
        }
        installPixels(image);
        usePixels();
    }

    private void publishPixels() {
        var ticket = decode;
        decode = null;
        try { restorePixels(ticket::take); }
        finally { ticket.close(); }
    }

    private void cancelDecode() {
        if (decode == null) return;
        decode.close();
        decode = null;
    }

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
            if (!requestPixels()) return false;
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
        if (!uploaded && !pending && pixels == null) {
            cancelDecode();
            restorePixels(() -> readPixels(source));
        }
        int texture = super.getId();
        if (!uploaded && !pending) {
            usePixels();
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

    public MaterialTexture derived(NativeImage pixels, UnaryOperator<NativeImage> transform) {
        var base = source;
        PreparedTexture.Source recipe = base == null ? null : () -> {
            try (var image = base.read()) { return transform.apply(image); }
        };
        return new MaterialTexture(pixels, material, recipe);
    }

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
        for (var texture : OWNED) {
            texture.cancelDecode();
            if (texture.pending) texture.releaseId();
        }
    }

    static void invalidatePbr(PbrMaterial material) {
        for (var texture : OWNED) if (texture.material == material) IrisPbrBridge.invalidate(texture.id);
    }

    @Override public void close() {
        if (!OWNED.remove(this)) return;
        cancelDecode();
        try { releaseId(); }
        finally {
            try { if (material != null) material.release(); }
            finally { if (readers == 0) releasePixels(); }
        }
    }
}
