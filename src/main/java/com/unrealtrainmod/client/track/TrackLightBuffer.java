/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisRenderStats;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** A private UV2 stream. Geometry and its element buffer never change during light refreshes. */
final class TrackLightBuffer implements AutoCloseable {
    private final TrackLighting lighting = new TrackLighting(new TrackVector(0, 0, 0));
    private final boolean fullBright;
    private long[] samples = new long[0];
    private ByteBuffer staging;
    private int handle;
    private boolean samplesChanged, dirty;

    TrackLightBuffer(boolean fullBright) { this.fullBright = fullBright; }

    void samples(long[] samples) {
        this.samples = samples;
        samplesChanged = true;
        if (staging == null || staging.capacity() != samples.length * Integer.BYTES) {
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc(samples.length * Integer.BYTES).order(ByteOrder.nativeOrder());
            dirty = true;
        }
    }

    boolean refresh(ClientLevel level, TrackRenderStats stats) {
        long start = stats == null ? 0 : System.nanoTime();
        boolean changed = !fullBright && lighting.refresh(level);
        if (samplesChanged || changed) {
            for (int i = 0; i < samples.length; i++) {
                int light = fullBright ? LightTexture.FULL_BRIGHT : lighting.value(level, samples[i]);
                int offset = i * Integer.BYTES;
                if (staging.getShort(offset) != (short)light
                        || staging.getShort(offset + 2) != (short)(light >>> 16)) {
                    staging.putShort(offset, (short)light).putShort(offset + 2, (short)(light >>> 16));
                    dirty = true;
                }
            }
            samplesChanged = false;
        }
        if (stats != null) stats.lightRefresh(System.nanoTime() - start,
                fullBright ? 0 : lighting.checkedSamples(), fullBright ? 0 : lighting.changedSamples());
        return dirty;
    }

    /** Called after every geometry upload because Minecraft reinstalls its interleaved attribute pointers. */
    void attach(VertexBuffer buffer) {
        buffer.bind();
        int attribute = buffer.getFormat().getElementAttributeNames().indexOf("UV2");
        if (handle == 0) handle = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, handle);
        GlStateManager._enableVertexAttribArray(attribute);
        GL30.glVertexAttribIPointer(attribute, 2, GL11.GL_SHORT, Integer.BYTES, 0L);
    }

    void upload(TrackRenderStats stats, boolean initial, boolean animated) {
        long start = stats == null ? 0 : System.nanoTime();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, handle);
        // Orphan only this small stream; geometry, indices and their VAO bindings stay intact.
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, staging, GL15.GL_DYNAMIC_DRAW);
        dirty = false;
        if (IrisCompat.active()) IrisRenderStats.upload(staging.capacity(), true);
        if (stats != null) {
            stats.gpuUpload(System.nanoTime() - start);
            stats.lightUploaded(staging.capacity(), initial, animated);
        }
    }

    int byteSize() { return staging == null ? 0 : staging.capacity(); }

    @Override public void close() {
        if (handle != 0) { GlStateManager._glDeleteBuffers(handle); handle = 0; }
        if (staging != null) { MemoryUtil.memFree(staging); staging = null; }
        samples = new long[0];
    }
}
