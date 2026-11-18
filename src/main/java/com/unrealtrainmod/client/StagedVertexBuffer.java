/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

/** Minecraft retains VAO/VBO ownership; static storage is filled in bounded, unpublished slices. */
public class StagedVertexBuffer extends VertexBuffer {
    private int vertexHandle;
    private final int vertices;
    public StagedVertexBuffer(int vertices) { super(Usage.STATIC); this.vertices = vertices; }

    public void allocate(MeshData template, int bytes) {
        bind();
        upload(template);
        vertexHandle = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, bytes, GL15.GL_STATIC_DRAW);
    }
    public void write(ByteBuffer data, int offset) {
        bind();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexHandle);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, data);
    }
    @Override public void draw() { GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertices); }

    public static final class Binding implements AutoCloseable {
        private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        private final int array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        @Override public void close() {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, array);
        }
    }
}
