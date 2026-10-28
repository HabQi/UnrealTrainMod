/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Textured rail buffers are independent of all vehicle GPU objects and queues. */
final class RailTrackGpuMesh implements TrackRenderMesh {
    // Vertex normals are already in world space. Do not inherit the previous GUI/entity light directions.
    private static final Vector3f LIGHT_0 = new Vector3f(0.2f, 1, -0.7f).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(-0.2f, 1, 0.7f).normalize();
    private final RailTrackMeshData data;
    private final ClientRailModelManager.Model model;
    private final AABB bounds;
    private final VertexBuffer[] buffers;
    private final TrackLighting lighting;
    private int uploaded;
    private int nextUpload;

    RailTrackGpuMesh(RailTrackMeshData data, ClientRailModelManager.Model model, boolean fullBright) {
        this.data = data;
        this.model = model;
        var b = data.bounds();
        bounds = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(0.001);
        buffers = new VertexBuffer[data.batches().size()];
        lighting = fullBright ? null : new TrackLighting(data.origin());
    }

    @Override public AABB bounds() { return bounds; }
    @Override public TrackVector origin() { return data.origin(); }
    @Override public boolean isEmpty() { return data.batches().isEmpty(); }
    @Override public boolean isUploaded() { return uploaded == buffers.length; }

    @Override
    public int upload(ClientLevel level, int budget) {
        if (lighting != null && nextUpload == buffers.length && lighting.refresh(level)) nextUpload = 0;
        if (budget == 0 || nextUpload == buffers.length) return 0;
        int used = 0;
        try {
            while (used < budget && nextUpload < buffers.length) {
                var mesh = data.batches().get(nextUpload).mesh();
                var buffer = buffers[nextUpload];
                if (buffer == null) {
                    buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    buffers[nextUpload] = buffer;
                }
                try (var bytes = new ByteBufferBuilder(Math.multiplyExact(mesh.indices().length, DefaultVertexFormat.NEW_ENTITY.getVertexSize()))) {
                    var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                    float[] v = mesh.vertices();
                    for (int index : mesh.indices()) {
                        int i = index * 8;
                        int light = lighting == null ? LightTexture.FULL_BRIGHT
                                : lighting.sample(level, v[i], v[i + 1], v[i + 2], v[i + 5], v[i + 6], v[i + 7]);
                        builder.addVertex(v[i], v[i + 1], v[i + 2]).setColor(mesh.colors()[index])
                                .setUv(v[i + 3], v[i + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                                .setNormal(v[i + 5], v[i + 6], v[i + 7]);
                    }
                    buffer.bind();
                    buffer.upload(builder.buildOrThrow());
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
    public void draw(Matrix4f modelView, Matrix4f projection) {
        for (int i = 0; i < uploaded; i++) {
            var batch = model.batches().get(data.batches().get(i).sourceIndex());
            var type = TrackRenderTypes.textured(batch.texture(), batch.doubleSided());
            type.setupRenderState();
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
                    buffers[i].draw();
                } finally {
                    shader.clear();
                    if (previousLight0 != null) shader.LIGHT0_DIRECTION.set(previousLight0);
                    if (previousLight1 != null) shader.LIGHT1_DIRECTION.set(previousLight1);
                }
            } finally { type.clearRenderState(); }
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
