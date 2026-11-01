/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.instancing;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

/**
 * Streams one frame worth of instance data into a single reusable VBO.
 * Layout: model matrix columns 0/16/32/48, normal matrix columns 64/76/88, packed light at 100.
 */
public final class InstanceBuffer implements AutoCloseable {
    public static final int STRIDE = 104;
    public static final int NORMAL_OFFSET = 64;
    public static final int LIGHT_OFFSET = 100;
    private static final int INITIAL_CAPACITY = 256;

    private ByteBuffer staging;
    private int capacity;
    private int count;
    private int handle = -1;
    private int uploadedBytes;

    public void begin() {
        count = 0;
        uploadedBytes = 0;
    }

    public void add(PoseStack.Pose pose, int light) {
        if (count == capacity) grow(Math.max(INITIAL_CAPACITY, capacity * 2));
        Matrix4f model = pose.pose();
        Matrix3f normal = pose.normal();
        staging.position(count * STRIDE);
        staging.putFloat(model.m00()).putFloat(model.m01()).putFloat(model.m02()).putFloat(model.m03());
        staging.putFloat(model.m10()).putFloat(model.m11()).putFloat(model.m12()).putFloat(model.m13());
        staging.putFloat(model.m20()).putFloat(model.m21()).putFloat(model.m22()).putFloat(model.m23());
        staging.putFloat(model.m30()).putFloat(model.m31()).putFloat(model.m32()).putFloat(model.m33());
        staging.putFloat(normal.m00()).putFloat(normal.m01()).putFloat(normal.m02());
        staging.putFloat(normal.m10()).putFloat(normal.m11()).putFloat(normal.m12());
        staging.putFloat(normal.m20()).putFloat(normal.m21()).putFloat(normal.m22());
        staging.putShort((short)(light & 0xffff)).putShort((short)(light >>> 16 & 0xffff));
        count++;
    }

    /** Orphans the reused storage, then streams only the active instances. */
    public void upload() {
        uploadedBytes = 0;
        if (count == 0) return;
        RenderSystem.assertOnRenderThread();
        if (handle < 0) handle = GlStateManager._glGenBuffers();
        int bytes = count * STRIDE;
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, handle);
        GlStateManager._glBufferData(GL15.GL_ARRAY_BUFFER, (long)capacity * STRIDE, GL15.GL_STREAM_DRAW);
        staging.position(0).limit(bytes);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, staging);
        staging.clear();
        uploadedBytes = bytes;
    }

    public int handle() { return handle; }
    public int uploadedBytes() { return uploadedBytes; }
    public long offsetOf(int instance) { return (long)instance * STRIDE; }

    private void grow(int newCapacity) {
        var replacement = MemoryUtil.memAlloc(Math.multiplyExact(newCapacity, STRIDE)).order(ByteOrder.nativeOrder());
        if (staging != null) {
            int bytes = count * STRIDE;
            staging.position(0).limit(bytes);
            replacement.put(staging);
            MemoryUtil.memFree(staging);
        }
        staging = replacement;
        capacity = newCapacity;
    }

    @Override
    public void close() {
        if (handle >= 0 && !RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::close);
            return;
        }
        if (handle >= 0) {
            GlStateManager._glDeleteBuffers(handle);
            handle = -1;
        }
        MemoryUtil.memFree(staging);
        staging = null;
        capacity = 0;
        count = 0;
        uploadedBytes = 0;
    }
}
