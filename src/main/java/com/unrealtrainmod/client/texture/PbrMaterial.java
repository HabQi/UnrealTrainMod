/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;

/** Base and alpha-derived textures share these channels; Iris owns independently closeable views. */
final class PbrMaterial {
    private final PbrSource source;
    private final Slot[] slots = new Slot[2];
    private final PbrLoadQueue.Job[] loads = new PbrLoadQueue.Job[2];
    private final boolean[] failed = new boolean[2];
    private int owners;

    PbrMaterial(PbrSource source) { this.source = source; }

    void retain() { owners++; }
    void release() { if (--owners == 0) clear(); }

    void clear() {
        for (int i = 0; i < slots.length; i++) {
            if (loads[i] != null) { loads[i].discard(); loads[i] = null; }
            if (slots[i] != null) { slots[i].release(); slots[i] = null; }
            failed[i] = false;
        }
    }

    AbstractTexture acquire(PbrSource.Channel channel, int defaultPixel, boolean blur) {
        int index = channel.ordinal();
        if (!source.has(channel) || failed[index]) return null;
        var slot = slots[index];
        if (slot == null) {
            if (loads[index] == null) loads[index] = PbrLoadQueue.enqueue(this, source, channel, defaultPixel, blur);
            return null;
        }
        slot.references++;
        return new View(slot);
    }

    void upload(PbrSource.Channel channel, NativeImage image, boolean blur) {
        int index = channel.ordinal();
        loads[index] = null;
        if (image == null) { failed[index] = true; return; }
        long start = System.nanoTime();
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            var slot = new Slot(new ImageTexture(image, blur), (long)image.getWidth() * image.getHeight() * 4);
            slots[index] = slot;
            PbrStats.uploaded(slot.bytes);
        } catch (RuntimeException exception) {
            failed[index] = true;
            PbrStats.failure();
            LoggerFactory.getLogger(PbrMaterial.class).warn("Cannot upload PBR {} texture; using Iris default", channel, exception);
            return;
        } finally {
            RenderSystem.bindTexture(previous);
            PbrStats.uploadedCpu(start);
        }
        // Discard cached default holders only after the shared channel is ready.
        MaterialTexture.invalidatePbr(this);
    }

    private static final class Slot {
        final ImageTexture texture;
        final long bytes;
        // One owner pin survives Iris holder invalidation; additional references belong to Iris views.
        int references = 1;
        Slot(ImageTexture texture, long bytes) { this.texture = texture; this.bytes = bytes; }
        void release() {
            if (--references != 0) return;
            texture.close();
            PbrStats.released(bytes);
        }
    }

    /** Upload once, then discard decoded pixels; no mip levels are introduced for dynamic base textures. */
    private static final class ImageTexture extends AbstractTexture {
        ImageTexture(NativeImage image, boolean blur) {
            try {
                TextureUtil.prepareImage(getId(), image.getWidth(), image.getHeight());
                bind();
                image.upload(0, 0, 0, false);
                setFilter(blur, false);
            } catch (RuntimeException | Error exception) { releaseId(); throw exception; }
        }
        @Override public void load(ResourceManager resources) {}
        @Override public void close() { releaseId(); }
    }

    private static final class View extends AbstractTexture {
        private final Slot slot;
        private boolean released;
        View(Slot slot) { this.slot = slot; }
        @Override public int getId() { return slot.texture.getId(); }
        @Override public void load(ResourceManager resources) {}
        @Override public void close() { releaseId(); }
        // Iris calls both close() and releaseId(). Neither may delete another holder's shared channel.
        @Override public void releaseId() {
            if (released) return;
            released = true;
            slot.release();
        }
    }
}
