/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.VertexBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

/** Minecraft owns the static VBO/VAO; this class owns the explicit native index buffer. */
final class TrackPageBuffer extends VertexBuffer {
    private int indexBuffer, indexType, indexSize;
    private final boolean arrays;

    TrackPageBuffer(boolean arrays) {
        super(Usage.STATIC);
        this.arrays = arrays;
    }

    int uploadIndices(int[] indices, int vertices) {
        boolean shorts = vertices <= 65_536;
        indexType = shorts ? GL11.GL_UNSIGNED_SHORT : GL11.GL_UNSIGNED_INT;
        indexSize = shorts ? Short.BYTES : Integer.BYTES;
        int size = indices.length * indexSize;
        var data = MemoryUtil.memAlloc(size).order(ByteOrder.nativeOrder());
        try {
            for (int index : indices) {
                if (shorts) data.putShort((short)index);
                else data.putInt(index);
            }
            data.flip();
            indexBuffer = GlStateManager._glGenBuffers();
            bind();
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        } finally { MemoryUtil.memFree(data); }
        return size;
    }

    void drawRanges(IntBuffer first, IntBuffer counts, PointerBuffer offsets, int rangeCount) {
        if (arrays) {
            if (rangeCount == 1) GL11.glDrawArrays(GL11.GL_TRIANGLES, first.get(0), counts.get(0));
            else GL14.glMultiDrawArrays(GL11.GL_TRIANGLES, first, counts);
        } else {
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            if (rangeCount == 1) GL11.glDrawElements(GL11.GL_TRIANGLES, counts.get(0), indexType,
                    (long)first.get(0) * indexSize);
            else {
                for (int i = 0; i < rangeCount; i++) offsets.put(i, (long)first.get(i) * indexSize);
                offsets.position(0).limit(rangeCount);
                GL14.glMultiDrawElements(GL11.GL_TRIANGLES, counts, indexType, offsets);
            }
        }
    }

    @Override public void close() {
        try {
            if (indexBuffer != 0) { GlStateManager._glDeleteBuffers(indexBuffer); indexBuffer = 0; }
        } finally { super.close(); }
    }
}
