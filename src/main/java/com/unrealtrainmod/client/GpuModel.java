/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

/** Shared static vertices and canonical indices; only sorted triangle indices change during a scene. */
public final class GpuModel implements AutoCloseable {
    public record Part(Mesh mesh, int firstIndex) {}

    private final Map<RenderBatch, Part> parts;
    private final List<Mesh> meshes;
    private final int vertexCount;
    private final int indexCount;
    private boolean closed;

    private GpuModel(Map<RenderBatch, Part> parts, List<Mesh> meshes, int vertexCount, int indexCount) {
        this.parts = parts;
        this.meshes = List.copyOf(meshes);
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
    }

    public int vertexCount() { return vertexCount; }
    public int indexCount() { return indexCount; }
    public long byteSize() { return (long)vertexCount * StaticModelShader.FORMAT.getVertexSize() + (long)indexCount * Integer.BYTES; }
    public Part part(RenderBatch batch) { return closed ? null : parts.get(batch); }

    public static GpuModel upload(List<RenderBatch> prepared) {
        RenderSystem.assertOnRenderThread();
        var groups = new IdentityHashMap<float[], List<RenderBatch>>();
        var ordered = new ArrayList<List<RenderBatch>>();
        for (var batch : prepared) {
            var group = groups.get(batch.mesh().vertices());
            if (group == null) {
                group = new ArrayList<>();
                groups.put(batch.mesh().vertices(), group);
                ordered.add(group);
            }
            group.add(batch);
        }
        var parts = new IdentityHashMap<RenderBatch, Part>();
        var meshes = new ArrayList<Mesh>();
        int vertices = 0, indices = 0;
        try {
            for (var group : ordered) {
                var original = group.getFirst().mesh();
                var offsets = new IdentityHashMap<IndexedMesh, Integer>();
                int count = 0;
                for (var batch : group) {
                    if (!offsets.containsKey(batch.mesh())) {
                        offsets.put(batch.mesh(), count);
                        count += batch.mesh().indices().length;
                    }
                }
                int[] canonical = new int[count];
                for (var entry : offsets.entrySet()) {
                    int[] selected = entry.getKey().indices();
                    System.arraycopy(selected, 0, canonical, entry.getValue(), selected.length);
                }
                var mesh = new Mesh(uploadMesh(new IndexedMesh(original.vertices(), original.colors(), canonical)));
                meshes.add(mesh);
                for (var batch : group) {
                    parts.put(batch, new Part(mesh, offsets.get(batch.mesh()) + batch.firstIndex()));
                }
                vertices += original.colors().length;
                indices += count;
            }
            return new GpuModel(parts, meshes, vertices, indices);
        } catch (RuntimeException | Error e) {
            meshes.forEach(Mesh::close);
            throw e;
        }
    }

    private static RangeVertexBuffer uploadMesh(IndexedMesh mesh) {
        int vertexBytes = Math.multiplyExact(mesh.colors().length, StaticModelShader.FORMAT.getVertexSize());
        int indexBytes = Math.multiplyExact(mesh.indices().length, Integer.BYTES);
        var gpu = new RangeVertexBuffer();
        try (var vertices = new ByteBufferBuilder(vertexBytes); var indices = new ByteBufferBuilder(indexBytes)) {
            ByteBuffer data = MemoryUtil.memByteBuffer(vertices.reserve(vertexBytes), vertexBytes).order(ByteOrder.nativeOrder());
            for (int vertex = 0, offset = 0; vertex < mesh.colors().length; vertex++, offset += 8) {
                float[] source = mesh.vertices();
                int color = mesh.colors()[vertex];
                data.putFloat(source[offset]).putFloat(source[offset + 1]).putFloat(source[offset + 2]);
                data.put((byte)(color >>> 16)).put((byte)(color >>> 8)).put((byte)color).put((byte)(color >>> 24));
                data.putFloat(source[offset + 3]).putFloat(source[offset + 4]);
                data.put(normalByte(source[offset + 5])).put(normalByte(source[offset + 6]))
                        .put(normalByte(source[offset + 7])).put((byte)0);
            }
            ByteBuffer indexData = MemoryUtil.memByteBuffer(indices.reserve(indexBytes), indexBytes).order(ByteOrder.nativeOrder());
            for (int index : mesh.indices()) indexData.putInt(index);
            var drawState = new MeshData.DrawState(StaticModelShader.FORMAT, mesh.colors().length,
                    mesh.indices().length, VertexFormat.Mode.TRIANGLES, VertexFormat.IndexType.INT);
            gpu.bind();
            // VertexBuffer.upload owns and closes both Result objects, even on upload failure.
            gpu.upload(new IndexedMeshData(vertices.build(), indices.build(), drawState));
            // VertexBuffer owns this canonical IBO. Capture its VAO binding once, without accessing private engine fields.
            gpu.canonicalIndexBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            return gpu;
        } catch (RuntimeException | Error e) {
            gpu.close();
            throw e;
        } finally {
            VertexBuffer.unbind();
        }
    }

    private static byte normalByte(float value) { return (byte)(Math.clamp(value, -1f, 1f) * 127f); }

    public static final class Mesh implements AutoCloseable {
        private final RangeVertexBuffer buffer;
        private int[] sorted = new int[0];
        private int sortedCount;
        private ByteBuffer staging;

        private Mesh(RangeVertexBuffer buffer) {
            this.buffer = buffer;
        }

        public void beginSortedIndices() { sortedCount = 0; }

        /** Returns this triangle's offset in the mesh's scene-local index buffer. */
        public int appendTriangle(int[] indices, int triangle) {
            int offset = sortedCount;
            if (sorted.length < offset + 3) sorted = Arrays.copyOf(sorted, Math.max(offset + 3, Math.max(256, sorted.length * 2)));
            System.arraycopy(indices, triangle * 3, sorted, offset, 3);
            sortedCount += 3;
            return offset;
        }

        public void uploadSortedIndices() {
            int bytes = Math.multiplyExact(sortedCount, Integer.BYTES);
            if (staging == null || staging.capacity() < bytes) {
                var replacement = MemoryUtil.memAlloc(Math.multiplyExact(sorted.length, Integer.BYTES)).order(ByteOrder.nativeOrder());
                MemoryUtil.memFree(staging);
                staging = replacement;
            }
            // Reuse native storage and bulk-copy the active indices, including when the instance count shrinks.
            staging.clear();
            staging.asIntBuffer().put(sorted, 0, sortedCount);
            staging.limit(bytes);
            buffer.uploadSortedIndices(staging);
        }

        /** The queue owns the RenderType and bound shader, including all uniform updates. */
        public void draw(boolean sorted, int firstIndex, int count) {
            buffer.firstIndex = firstIndex;
            buffer.count = count;
            buffer.bindIndices(sorted);
            buffer.draw();
        }

        @Override
        public void close() {
            try {
                buffer.close();
            } finally {
                MemoryUtil.memFree(staging);
                staging = null;
            }
        }
    }

    /** Keeps Minecraft's buffer upload/ownership while providing indexed range draws. */
    private static final class RangeVertexBuffer extends VertexBuffer {
        private int firstIndex, count;
        private int canonicalIndexBuffer;
        private int sortedIndexBuffer = -1;
        private RangeVertexBuffer() { super(Usage.STATIC); }

        private void bindIndices(boolean sorted) {
            super.bind();
            // ELEMENT_ARRAY_BUFFER is VAO state; select the appropriate IBO on every draw, including shared instances/scenes.
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, sorted ? sortedIndexBuffer : canonicalIndexBuffer);
        }

        private void uploadSortedIndices(ByteBuffer indices) {
            if (sortedIndexBuffer < 0) sortedIndexBuffer = GlStateManager._glGenBuffers();
            bindIndices(true);
            // Only this streaming IBO changes storage. The canonical IBO and static VBO remain untouched.
            RenderSystem.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STREAM_DRAW);
        }

        @Override public void draw() {
            RenderSystem.assertOnRenderThread();
            GlStateManager._drawElements(GL11.GL_TRIANGLES, count, GL11.GL_UNSIGNED_INT, (long)firstIndex * Integer.BYTES);
        }

        @Override public void close() {
            try {
                super.close();
            } finally {
                if (sortedIndexBuffer >= 0) {
                    RenderSystem.glDeleteBuffers(sortedIndexBuffer);
                    sortedIndexBuffer = -1;
                }
            }
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (!closed) {
            meshes.forEach(Mesh::close);
            closed = true;
        }
    }

    /** Minecraft's MeshData constructor exposes only sequential indices; supply our explicit index payload. */
    private static final class IndexedMeshData extends MeshData {
        private final ByteBufferBuilder.Result indices;
        private IndexedMeshData(ByteBufferBuilder.Result vertices, ByteBufferBuilder.Result indices, DrawState drawState) {
            super(vertices, drawState);
            this.indices = indices;
        }
        @Override public ByteBuffer indexBuffer() { return indices.byteBuffer(); }
        @Override public void close() {
            try { super.close(); } finally { indices.close(); }
        }
    }
}
