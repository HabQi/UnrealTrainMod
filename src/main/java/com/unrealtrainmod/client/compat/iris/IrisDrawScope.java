/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Owned drawing range: material setup may be shared, every draw still applies the complete Iris shader. */
public final class IrisDrawScope implements AutoCloseable {
    private static IrisDrawScope current;
    private final IrisDrawScope previous = current;
    private final GlStateBackup state = new GlStateBackup();
    private final ShaderInstance shader = RenderSystem.getShader();
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] viewport = new int[4], textures = new int[3], bindings = new int[3];
    private final float[] color = RenderSystem.getShaderColor().clone();
    private final int[] identity, drawIdentity;
    private final boolean entity;
    private final Map<Integer, int[]> constants = new LinkedHashMap<>();
    private RenderType activeType;

    public IrisDrawScope() { this(false); }
    public static IrisDrawScope entity() { return new IrisDrawScope(true); }
    public static IrisDrawScope current() { return current; }

    private IrisDrawScope(boolean entity) {
        this.entity = entity;
        RenderSystem.backupGlState(state);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        for (int i = 0; i < 3; i++) {
            textures[i] = RenderSystem.getShaderTexture(i);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            bindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        RenderSystem.activeTexture(activeTexture);
        identity = IrisCompat.currentIdentity();
        drawIdentity = entity ? identity : new int[3];
        if (!entity) IrisCompat.restoreTrackIdentity(drawIdentity);
        current = this;
    }

    private boolean use(RenderType type) {
        if (entity) type = IrisCompat.entityType(type);
        if (activeType == type) return false;
        endBatch();
        type.setupRenderState();
        activeType = type;
        return true;
    }

    /** Call before handing control back to MultiBufferSource, whose wrappers own their own entity phase. */
    public void endBatch() {
        if (activeType != null) {
            var type = activeType;
            activeType = null;
            type.clearRenderState();
        }
    }

    private void saveConstant(int attribute) {
        constants.computeIfAbsent(attribute, slot -> {
            int[] value = new int[4];
            GL30.glGetVertexAttribIiv(slot, GL20.GL_CURRENT_VERTEX_ATTRIB, value);
            return value;
        });
    }

    public static boolean draw(VertexBuffer buffer, RenderType type, Matrix4f modelView, Matrix4f projection) {
        return current.drawInternal(buffer, null, type, modelView, projection, 0);
    }

    public boolean drawCached(IrisGeometryCache.Mesh mesh, RenderType type, Matrix4f modelView, Matrix4f projection, int light) {
        return drawInternal(mesh.buffer, mesh, type, modelView, projection, light);
    }

    private boolean drawInternal(VertexBuffer buffer, IrisGeometryCache.Mesh mesh, RenderType type,
                              Matrix4f modelView, Matrix4f projection, int light) {
        boolean changed = use(type);
        var shader = RenderSystem.getShader();
        try {
            buffer.bind();
            if (mesh != null) {
                saveConstant(mesh.lightAttribute);
                saveConstant(mesh.identityAttribute);
                GL30.glVertexAttribI2i(mesh.lightAttribute, light & 0xffff, light >>> 16 & 0xffff);
                GL30.glVertexAttribI3i(mesh.identityAttribute,
                        drawIdentity[0] & 0xffff, drawIdentity[1] & 0xffff, drawIdentity[2] & 0xffff);
            }
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            buffer.draw();
            return changed;
        } finally {
            try { shader.clear(); }
            finally { VertexBuffer.unbind(); }
        }
    }

    @Override public void close() {
        try { endBatch(); }
        finally {
            for (var entry : constants.entrySet()) {
                int[] v = entry.getValue();
                GL30.glVertexAttribI4i(entry.getKey(), v[0], v[1], v[2], v[3]);
            }
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, array);
            // Iris clear() invalidates its extra sampler/uniform caches for the next complete apply().
            for (int i = 0; i < 3; i++) {
                RenderSystem.setShaderTexture(i, textures[i]);
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                RenderSystem.bindTexture(bindings[i]);
            }
            RenderSystem.activeTexture(activeTexture);
            RenderSystem.setShader(() -> shader);
            GlStateManager._glUseProgram(program);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.restoreGlState(state);
            IrisCompat.restoreTrackIdentity(identity);
            current = previous;
        }
    }
}
