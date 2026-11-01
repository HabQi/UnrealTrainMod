/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** Textured rail buffers are independent of all vehicle GPU objects and queues. */
final class RailTrackGpuMesh implements TrackRenderMesh {
    // Vertex normals are already in world space. Do not inherit the previous GUI/entity light directions.
    private static final Vector3f LIGHT_0 = new Vector3f(0.2f, 1, -0.7f).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(-0.2f, 1, 0.7f).normalize();
    private final RailTrackMeshData data;
    private final AABB bounds;
    private final VertexBuffer[] buffers;
    private final RenderType[] types;
    private final TrackLighting lighting;
    private int uploaded;
    private int nextUpload;

    RailTrackGpuMesh(RailTrackMeshData data, boolean fullBright) {
        this.data = data;
        var b = data.bounds();
        bounds = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(0.001);
        buffers = new VertexBuffer[data.batches().size()];
        types = new RenderType[buffers.length];
        for (int i = 0; i < types.length; i++) {
            var batch = data.batches().get(i);
            types[i] = TrackRenderTypes.textured(batch.texture(), batch.doubleSided());
        }
        lighting = fullBright ? null : new TrackLighting(data.origin());
    }

    @Override public AABB bounds() { return bounds; }
    @Override public TrackVector origin() { return data.origin(); }
    @Override public boolean isEmpty() { return data.batches().isEmpty(); }
    @Override public boolean isUploaded() { return uploaded == buffers.length; }
    @Override public int source() { return TrackRenderStats.RAIL_MODEL; }
    @Override public int drawCalls() { return uploaded; }

    @Override
    public int drawVertices() {
        // Preserve the submitted-reference statistic so indexed and expanded versions are comparable.
        int vertices = 0;
        for (int i = 0; i < uploaded; i++) vertices += data.batches().get(i).mesh().indices().length;
        return vertices;
    }

    @Override
    public int drawTriangles() {
        int triangles = 0;
        for (int i = 0; i < uploaded; i++) triangles += data.batches().get(i).mesh().indices().length / 3;
        return triangles;
    }

    @Override
    public int storedVertices() {
        int vertices = 0;
        for (int i = 0; i < uploaded; i++) vertices += data.batches().get(i).mesh().colors().length;
        return vertices;
    }

    @Override
    public int upload(ClientLevel level, int budget, TrackRenderStats stats) {
        if (lighting != null && nextUpload == buffers.length) {
            long start = stats == null ? 0L : System.nanoTime();
            boolean changed = lighting.refresh(level);
            if (stats != null) {
                stats.lightRefresh(System.nanoTime() - start, lighting.checkedSamples(), lighting.changedSamples());
            }
            if (changed) nextUpload = 0;
        }
        if (budget == 0 || nextUpload == buffers.length) return 0;
        int used = 0;
        try {
            while (used < budget && nextUpload < buffers.length) {
                var mesh = data.batches().get(nextUpload).mesh();
                var buffer = buffers[nextUpload];
                boolean first = buffer == null;
                if (first) {
                    buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    buffers[nextUpload] = buffer;
                }
                int vertexSize = DefaultVertexFormat.NEW_ENTITY.getVertexSize();
                int vertexCount = mesh.colors().length;
                boolean shortIndices = vertexCount <= 65_536;
                var indexType = shortIndices ? VertexFormat.IndexType.SHORT : VertexFormat.IndexType.INT;
                int vertexBytes = Math.multiplyExact(vertexCount, vertexSize);
                int indexBytes = Math.multiplyExact(mesh.indices().length, shortIndices ? Short.BYTES : Integer.BYTES);
                try (var bytes = new ByteBufferBuilder(vertexBytes); var indices = new ByteBufferBuilder(indexBytes)) {
                    ByteBuffer vertices = MemoryUtil.memByteBuffer(bytes.reserve(vertexBytes), vertexBytes).order(ByteOrder.nativeOrder());
                    ByteBuffer elements = MemoryUtil.memByteBuffer(indices.reserve(indexBytes), indexBytes).order(ByteOrder.nativeOrder());
                    float[] v = mesh.vertices();
                    long packStart = stats == null ? 0L : System.nanoTime();
                    for (int index = 0; index < vertexCount; index++) {
                        int i = index * 8;
                        int light = lighting == null ? LightTexture.FULL_BRIGHT
                                : lighting.sample(level, v[i], v[i + 1], v[i + 2], v[i + 5], v[i + 6], v[i + 7]);
                        int color = mesh.colors()[index];
                        // NEW_ENTITY: position, RGBA, UV, overlay shorts, light shorts, signed normal and padding.
                        vertices.putFloat(v[i]).putFloat(v[i + 1]).putFloat(v[i + 2]);
                        vertices.put((byte)(color >>> 16)).put((byte)(color >>> 8)).put((byte)color).put((byte)(color >>> 24));
                        vertices.putFloat(v[i + 3]).putFloat(v[i + 4]);
                        vertices.putShort((short)OverlayTexture.NO_OVERLAY).putShort((short)(OverlayTexture.NO_OVERLAY >>> 16));
                        vertices.putShort((short)light).putShort((short)(light >>> 16));
                        vertices.put(normalByte(v[i + 5])).put(normalByte(v[i + 6])).put(normalByte(v[i + 7])).put((byte)0);
                    }
                    for (int index : mesh.indices()) {
                        if (shortIndices) elements.putShort((short)index);
                        else elements.putInt(index);
                    }
                    if (stats != null) stats.packed(System.nanoTime() - packStart);
                    long uploadStart = stats == null ? 0L : System.nanoTime();
                    buffer.bind();
                    var drawState = new MeshData.DrawState(DefaultVertexFormat.NEW_ENTITY, vertexCount,
                            mesh.indices().length, VertexFormat.Mode.TRIANGLES, indexType);
                    // VertexBuffer.upload owns and closes both result objects, including on failure.
                    buffer.upload(new IndexedMeshData(bytes.build(), indices.build(), drawState));
                    if (stats != null) {
                        stats.gpuUpload(System.nanoTime() - uploadStart);
                        stats.uploaded(vertexBytes, indexBytes, Math.addExact(vertexBytes, indexBytes), first);
                    }
                }
                nextUpload++;
                uploaded = Math.max(uploaded, nextUpload);
                used++;
            }
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        } finally { VertexBuffer.unbind(); }
        return used;
    }

    @Override
    public void draw(Matrix4f modelView, Matrix4f projection, TrackRenderStats stats) {
        for (int i = 0; i < uploaded; i++) {
            var type = types[i];
            type.setupRenderState();
            if (stats != null) stats.renderTypeSetup();
            try {
                buffers[i].bind();
                var shader = RenderSystem.getShader();
                shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
                Vector3f previousLight0 = readLight(shader.LIGHT0_DIRECTION);
                Vector3f previousLight1 = readLight(shader.LIGHT1_DIRECTION);
                try {
                    // AFTER_SOLID_BLOCKS precedes Lighting.setupLevel(); diffuse shading is separate from world light.
                    if (previousLight0 != null) shader.LIGHT0_DIRECTION.set(LIGHT_0);
                    if (previousLight1 != null) shader.LIGHT1_DIRECTION.set(LIGHT_1);
                    shader.apply();
                    if (stats != null) stats.legacyShaderBind();
                    buffers[i].draw();
                } finally {
                    shader.clear();
                    if (previousLight0 != null) shader.LIGHT0_DIRECTION.set(previousLight0);
                    if (previousLight1 != null) shader.LIGHT1_DIRECTION.set(previousLight1);
                }
            } finally { type.clearRenderState(); }
        }
    }

    @Override
    public void draw(TrackRenderState state, Matrix4f modelView, Matrix4f projection) {
        for (int i = 0; i < uploaded; i++) {
            state.drawTextured(types[i], buffers[i], modelView, projection);
        }
    }

    @Override
    public void enqueue(TrackDrawQueue queue, Matrix4f modelView, int structure) {
        long chunk = ChunkPos.asLong((int)(data.origin().x() / 16), (int)(data.origin().z() / 16));
        for (int i = 0; i < uploaded; i++) {
            var mesh = data.batches().get(i).mesh();
            queue.addTextured(types[i], buffers[i], modelView, structure,
                    mesh.indices().length, mesh.colors().length, chunk);
        }
    }

    private static byte normalByte(float value) {
        return (byte)(Math.clamp(value, -1f, 1f) * 127f);
    }

    /** Explicit indices replace Minecraft's default sequential index buffer. */
    private static final class IndexedMeshData extends MeshData {
        private final ByteBufferBuilder.Result indices;

        private IndexedMeshData(ByteBufferBuilder.Result vertices, ByteBufferBuilder.Result indices, DrawState state) {
            super(vertices, state);
            this.indices = indices;
        }

        @Override public ByteBuffer indexBuffer() { return indices.byteBuffer(); }

        @Override
        public void close() {
            try { super.close(); }
            finally { indices.close(); }
        }
    }

    private static Vector3f readLight(Uniform uniform) {
        if (uniform == null) return null;
        var values = uniform.getFloatBuffer();
        return new Vector3f(values.get(0), values.get(1), values.get(2));
    }

    @Override
    public void close() {
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null) buffers[i].close();
            buffers[i] = null;
        }
        uploaded = 0;
        nextUpload = 0;
    }
}
