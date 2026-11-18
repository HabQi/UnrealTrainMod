/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

/** Minecraft owns the static VBO/VAO; this class owns the explicit native index buffer. */
final class TrackPageBuffer extends com.unrealtrainmod.client.StagedVertexBuffer {
    private int indexBuffer, indexType, indexSize;
    private final boolean arrays;
    private final int drawCount;

    TrackPageBuffer(boolean arrays) {
        this(arrays, 0);
    }
    TrackPageBuffer(boolean arrays, int vertices) {
        super(vertices);
        this.arrays = arrays;
        drawCount = vertices / 4 * 6;
    }
    @Override public void draw() {
        if (arrays) super.draw();
        else {
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GL11.glDrawElements(GL11.GL_TRIANGLES, drawCount, indexType, 0L);
        }
    }

    void allocateIndices(int bytes, int vertices) {
        indexType = vertices <= 65_536 ? GL11.GL_UNSIGNED_SHORT : GL11.GL_UNSIGNED_INT;
        indexSize = vertices <= 65_536 ? Short.BYTES : Integer.BYTES;
        indexBuffer = GlStateManager._glGenBuffers();
        bind(); GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, bytes, GL15.GL_STATIC_DRAW);
    }
    void writeIndices(java.nio.ByteBuffer bytes, int offset) {
        bind(); GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, offset, bytes);
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
