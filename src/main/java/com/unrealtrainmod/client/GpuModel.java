/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.renderer.instancing.InstanceBuffer;
import com.unrealtrainmod.model.IndexedMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

/** Shared static vertices and canonical indices; only sorted triangle indices change during a scene. */
public final class GpuModel implements AutoCloseable {
    public record Part(Mesh mesh, int firstIndex) {}
    private record VertexSource(float[] vertices, int[] colors) {}

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

    private record PackedMesh(ByteBuffer vertices, ByteBuffer indices, Map<RenderBatch, Integer> offsets, int vertexCount) {}
    public static final class Prepared implements AutoCloseable {
        private final List<PackedMesh> meshes = new ArrayList<>();
        public long byteSize() {
            long bytes = 0;
            for (var mesh : meshes) bytes += (long)mesh.vertices.capacity() + mesh.indices.capacity();
            return bytes;
        }
        @Override public void close() {
            for (var mesh : meshes) { MemoryUtil.memFree(mesh.vertices); MemoryUtil.memFree(mesh.indices); }
            meshes.clear();
        }
    }

    /** Canonical indices and native vertex bytes are produced without touching GL or renderer state. */
    public static Prepared prepare(List<RenderBatch> batches) {
        var result = new Prepared();
        var groups = new java.util.LinkedHashMap<VertexSource, List<RenderBatch>>();
        for (var batch : batches) groups.computeIfAbsent(new VertexSource(batch.mesh().vertices(), batch.mesh().colors()),
                ignored -> new ArrayList<>()).add(batch);
        try {
            for (var group : groups.values()) {
                var original = group.getFirst().mesh();
                var offsets = new IdentityHashMap<IndexedMesh, Integer>();
                var ranges = new IdentityHashMap<RenderBatch, Integer>();
                int count = 0;
                for (var batch : group) if (!offsets.containsKey(batch.mesh())) {
                    offsets.put(batch.mesh(), count); count = Math.addExact(count, batch.mesh().indices().length);
                }
                var vertices = MemoryUtil.memAlloc(Math.multiplyExact(original.colors().length, StaticModelShader.FORMAT.getVertexSize()))
                        .order(ByteOrder.nativeOrder());
                ByteBuffer indices = null;
                try {
                    indices = MemoryUtil.memAlloc(Math.multiplyExact(count, Integer.BYTES)).order(ByteOrder.nativeOrder());
                    for (int vertex = 0, i = 0; vertex < original.colors().length; vertex++, i += 8) {
                        var v = original.vertices(); int color = original.colors()[vertex];
                        vertices.putFloat(v[i]).putFloat(v[i + 1]).putFloat(v[i + 2]);
                        vertices.put((byte)(color >>> 16)).put((byte)(color >>> 8)).put((byte)color).put((byte)(color >>> 24));
                        vertices.putFloat(v[i + 3]).putFloat(v[i + 4]);
                        vertices.put(normalByte(v[i + 5])).put(normalByte(v[i + 6])).put(normalByte(v[i + 7])).put((byte)0);
                    }
                    for (var entry : offsets.entrySet()) {
                        indices.position(entry.getValue() * Integer.BYTES);
                        indices.asIntBuffer().put(entry.getKey().indices());
                    }
                    for (var batch : group) ranges.put(batch, offsets.get(batch.mesh()) + batch.firstIndex());
                    vertices.clear(); indices.clear();
                    result.meshes.add(new PackedMesh(vertices, indices, ranges, original.colors().length));
                } catch (RuntimeException | Error exception) {
                    MemoryUtil.memFree(vertices); MemoryUtil.memFree(indices); throw exception;
                }
            }
            return result;
        } catch (RuntimeException | Error exception) { result.close(); throw exception; }
    }

    /** Private buffers stay unpublished until every source range has been uploaded. */
    public static final class Upload implements AutoCloseable {
        private final Prepared prepared;
        private final List<Mesh> meshes = new ArrayList<>();
        private final Map<RenderBatch, Part> parts = new IdentityHashMap<>();
        private int group, offset, vertices, indices;
        private boolean index, transferred;
        public Upload(Prepared prepared) { this.prepared = prepared; }
        public boolean complete() { return group == prepared.meshes.size(); }
        public boolean step() {
            if (complete()) return true;
            var source = prepared.meshes.get(group);
            var data = index ? source.indices : source.vertices;
            int count = Math.min(ModelUploads.SLICE_BYTES, data.capacity() - offset);
            if (!ModelUploads.run(count, () -> {
                int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
                int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
                try {
                    if (meshes.size() == group) {
                        var buffer = new RangeVertexBuffer();
                        meshes.add(new Mesh(buffer));
                        buffer.initialize(source);
                    }
                    var buffer = meshes.get(group).buffer;
                    buffer.bind();
                    int target = index ? GL15.GL_ELEMENT_ARRAY_BUFFER : GL15.GL_ARRAY_BUFFER;
                    GlStateManager._glBindBuffer(target, index ? buffer.canonicalIndexBuffer : buffer.staticVertexBuffer);
                    var slice = data.duplicate().position(offset).limit(offset + count).slice();
                    GL15.glBufferSubData(target, offset, slice);
                } finally {
                    BufferUploader.invalidate();
                    GlStateManager._glBindVertexArray(oldVao);
                    GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArray);
                }
            })) return false;
            offset += count;
            if (offset == data.capacity()) {
                offset = 0;
                if (!index) index = true;
                else {
                    var mesh = meshes.get(group);
                    source.offsets.forEach((batch, first) -> parts.put(batch, new Part(mesh, first)));
                    vertices += source.vertexCount; indices += source.indices.capacity() / Integer.BYTES;
                    group++; index = false;
                }
            }
            return complete();
        }
        public GpuModel finish() {
            transferred = true;
            return new GpuModel(parts, meshes, vertices, indices);
        }
        @Override public void close() { if (!transferred) meshes.forEach(Mesh::close); }
    }

    public static GpuModel upload(List<RenderBatch> prepared) {
        RenderSystem.assertOnRenderThread();
        var groups = new HashMap<VertexSource, List<RenderBatch>>();
        var ordered = new ArrayList<List<RenderBatch>>();
        for (var batch : prepared) {
            var key = new VertexSource(batch.mesh().vertices(), batch.mesh().colors());
            var group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
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
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
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
            // VertexBuffer owns both static buffers. Capture their upload bindings without accessing private fields.
            gpu.staticVertexBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            gpu.canonicalIndexBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            return gpu;
        } catch (RuntimeException | Error e) {
            gpu.close();
            throw e;
        } finally {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(oldVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArray);
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

        /** The queue releases instance-buffer references once per mesh at the end of the opaque pass. */
        public void drawInstanced(int firstIndex, int count, int instanceBuffer, long instanceOffset,
                                  int instanceCount, int[] attributes) {
            RenderSystem.assertOnRenderThread();
            buffer.bindInstanced(attributes);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
            for (int column = 0; column < 4; column++) {
                GlStateManager._vertexAttribPointer(attributes[column], 4, GL11.GL_FLOAT, false,
                        InstanceBuffer.STRIDE, instanceOffset + (long)column * 16);
            }
            for (int column = 0; column < 3; column++) {
                GlStateManager._vertexAttribPointer(attributes[4 + column], 3, GL11.GL_FLOAT, false,
                        InstanceBuffer.STRIDE, instanceOffset + InstanceBuffer.NORMAL_OFFSET + (long)column * 12);
            }
            GlStateManager._vertexAttribIPointer(attributes[7], 2, GL11.GL_UNSIGNED_SHORT,
                    InstanceBuffer.STRIDE, instanceOffset + InstanceBuffer.LIGHT_OFFSET);
            // GL 3.3 has no base-instance draw; the batch offset lives in the attribute pointers.
            GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, count, GL11.GL_UNSIGNED_INT,
                    (long)firstIndex * Integer.BYTES, instanceCount);
        }

        public void releaseInstanceBuffer() {
            RenderSystem.assertOnRenderThread();
            buffer.releaseInstanceBuffer();
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
        private int staticVertexBuffer;
        private int canonicalIndexBuffer;
        private int sortedIndexBuffer = -1;
        private int instancedArray = -1;
        private int[] instanceAttributes;
        private RangeVertexBuffer() { super(Usage.STATIC); }

        private void initialize(PackedMesh packed) {
            // Let Minecraft establish its owned VAO/VBO/IBO and format with a tiny placeholder.
            int size = StaticModelShader.FORMAT.getVertexSize() * 3;
            try (var vertices = new ByteBufferBuilder(size); var indices = new ByteBufferBuilder(12)) {
                MemoryUtil.memSet(vertices.reserve(size), 0, size);
                MemoryUtil.memSet(indices.reserve(12), 0, 12);
                bind();
                upload(new IndexedMeshData(vertices.build(), indices.build(), new MeshData.DrawState(
                        StaticModelShader.FORMAT, 3, 3, VertexFormat.Mode.TRIANGLES, VertexFormat.IndexType.INT)));
                staticVertexBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
                canonicalIndexBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, packed.vertices.capacity(), GL15.GL_STATIC_DRAW);
                GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, packed.indices.capacity(), GL15.GL_STATIC_DRAW);
            }
        }

        private void bindIndices(boolean sorted) {
            super.bind();
            // ELEMENT_ARRAY_BUFFER is VAO state; select the appropriate IBO on every draw, including shared instances/scenes.
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, sorted ? sortedIndexBuffer : canonicalIndexBuffer);
        }

        /** Separate VAO keeps enabled instance attributes and divisors out of normal and sorted draws. */
        private void bindInstanced(int[] attributes) {
            BufferUploader.invalidate();
            if (instanceAttributes != attributes) {
                if (instancedArray >= 0) RenderSystem.glDeleteVertexArrays(instancedArray);
                instancedArray = GlStateManager._glGenVertexArrays();
                instanceAttributes = attributes;
                GlStateManager._glBindVertexArray(instancedArray);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, staticVertexBuffer);
                StaticModelShader.FORMAT.setupBufferState();
                GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, canonicalIndexBuffer);
                for (int attribute : attributes) {
                    GlStateManager._enableVertexAttribArray(attribute);
                    GL33.glVertexAttribDivisor(attribute, 1);
                }
            } else {
                GlStateManager._glBindVertexArray(instancedArray);
            }
        }

        private void releaseInstanceBuffer() {
            if (instancedArray < 0) return;
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(instancedArray);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, staticVertexBuffer);
            // Remove queue-owned VBO references, retaining the cached enables and divisors for the next pass.
            for (int attribute : instanceAttributes) {
                GlStateManager._vertexAttribPointer(attribute, 4, GL11.GL_FLOAT, false, 0, 0L);
            }
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
                if (instancedArray >= 0) {
                    RenderSystem.glDeleteVertexArrays(instancedArray);
                    instancedArray = -1;
                    instanceAttributes = null;
                }
            } finally {
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
