/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.model.IndexedMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

/** Shared, render-thread-owned static geometry for opaque/cutout parts. */
public final class GpuModel implements AutoCloseable {
    public record PreparedPart(ResourceLocation texture, boolean doubleSided, IndexedMesh mesh) {}
    private record Part(ResourceLocation texture, boolean doubleSided, VertexBuffer buffer) {
        private RenderType renderType() {
            return doubleSided ? RenderType.entityCutoutNoCull(texture) : RenderType.entityCutout(texture);
        }
    }

    private final List<Part> parts;
    private final int vertexCount;
    private final int indexCount;
    private boolean closed;

    private GpuModel(List<Part> parts, int vertexCount, int indexCount) {
        this.parts = List.copyOf(parts);
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
    }

    public int vertexCount() { return vertexCount; }
    public int indexCount() { return indexCount; }
    public long byteSize() { return (long)vertexCount * StaticModelShader.FORMAT.getVertexSize() + (long)indexCount * Integer.BYTES; }

    public static GpuModel upload(List<PreparedPart> prepared) {
        RenderSystem.assertOnRenderThread();
        List<Part> parts = new ArrayList<>();
        int vertices = 0, indices = 0;
        try {
            for (var part : prepared) {
                parts.add(new Part(part.texture(), part.doubleSided(), uploadMesh(part.mesh())));
                vertices += part.mesh().colors().length;
                indices += part.mesh().indices().length;
            }
            // Only opaque geometry is reordered.
            parts.sort(Comparator.comparing((Part part) -> part.texture().toString()).thenComparing(Part::doubleSided));
            return new GpuModel(parts, vertices, indices);
        } catch (RuntimeException | Error e) {
            parts.forEach(part -> part.buffer().close());
            throw e;
        }
    }

    private static VertexBuffer uploadMesh(IndexedMesh mesh) {
        int vertexBytes = Math.multiplyExact(mesh.colors().length, StaticModelShader.FORMAT.getVertexSize());
        int indexBytes = Math.multiplyExact(mesh.indices().length, Integer.BYTES);
        VertexBuffer gpu = new VertexBuffer(VertexBuffer.Usage.STATIC);
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
            return gpu;
        } catch (RuntimeException | Error e) {
            gpu.close();
            throw e;
        } finally {
            VertexBuffer.unbind();
        }
    }

    private static byte normalByte(float value) { return (byte)(Math.clamp(value, -1f, 1f) * 127f); }

    /** @return false when the caller should use the existing buffered compatibility path. */
    public boolean draw(PoseStack.Pose pose, int packedLight) {
        RenderSystem.assertOnRenderThread();
        var shaderState = StaticModelShader.state();
        if (closed || shaderState == null) return false;
        if (parts.isEmpty()) return true;
        shaderState.prepare(pose, packedLight);
        var previousShader = RenderSystem.getShader();
        int texture0 = RenderSystem.getShaderTexture(0), texture1 = RenderSystem.getShaderTexture(1), texture2 = RenderSystem.getShaderTexture(2);
        RenderType active = null;
        try {
            for (var part : parts) {
                RenderType type = part.renderType();
                if (type != active) {
                    if (active != null) active.clearRenderState();
                    active = type;
                    // Use Minecraft's texture, lightmap, overlay, depth and culling states.
                    // Geometry format/mode belong to our VBO, not this RenderType's buffer builder.
                    active.setupRenderState();
                }
                part.buffer().bind();
                // ModelMat is separate from the global model-view matrix, matching the old CPU pose transform.
                part.buffer().drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), shaderState.shader());
            }
            return true;
        } finally {
            VertexBuffer.unbind();
            if (active != null) active.clearRenderState();
            RenderSystem.setShaderTexture(0, texture0);
            RenderSystem.setShaderTexture(1, texture1);
            RenderSystem.setShaderTexture(2, texture2);
            RenderSystem.setShader(() -> previousShader);
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (!closed) {
            parts.forEach(part -> part.buffer().close());
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
