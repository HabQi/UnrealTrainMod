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

/** Owned drawing range: entity draws retain Iris state until the material or matrices change. */
public final class IrisDrawScope implements AutoCloseable {
    private static IrisDrawScope current;
    private final IrisRenderStats.Scope metrics = IrisRenderStats.beginScope();
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
    private ShaderInstance appliedShader;
    private final Matrix4f appliedView = new Matrix4f(), appliedProjection = new Matrix4f();

    public IrisDrawScope() { this(false, false); }
    public static IrisDrawScope entity() { return new IrisDrawScope(true, false); }
    /** HandRenderer already owns the hand phase and projection; do not wrap it as an entity pass. */
    public static IrisDrawScope hand() { return new IrisDrawScope(false, true); }
    public static IrisDrawScope current() { return current; }

    private IrisDrawScope(boolean entity, boolean hand) {
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
        drawIdentity = entity || hand ? identity : new int[3];
        if (!entity && !hand) IrisCompat.restoreTrackIdentity(drawIdentity);
        current = this;
        if (metrics != null) metrics.captured();
    }

    private boolean use(RenderType type) {
        if (entity) type = IrisCompat.entityType(type);
        if (activeType == type) return false;
        endBatch();
        type.setupRenderState();
        activeType = type;
        if (metrics != null) metrics.setups++;
        return true;
    }

    /** Call before handing control back to MultiBufferSource, whose wrappers own their own entity phase. */
    public void endBatch() {
        try {
            if (appliedShader != null) {
                var shader = appliedShader;
                appliedShader = null;
                shader.clear();
            }
        } finally {
            if (activeType != null) {
                var type = activeType;
                activeType = null;
                type.clearRenderState();
            }
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
        return current.drawInternal(buffer, null, 0, 0, type, modelView, projection, 0);
    }

    public boolean drawCached(IrisGeometryCache.Mesh mesh, RenderType type, Matrix4f modelView, Matrix4f projection, int light) {
        return drawInternal(mesh.storage.buffer, mesh, mesh.firstVertex, mesh.vertices, type, modelView, projection, light);
    }

    boolean drawCachedRange(IrisGeometryCache.Mesh mesh, int firstVertex, int vertices, RenderType type,
                            Matrix4f modelView, Matrix4f projection, int light) {
        return drawInternal(mesh.storage.buffer, mesh, firstVertex, vertices, type, modelView, projection, light);
    }

    private boolean drawInternal(VertexBuffer buffer, IrisGeometryCache.Mesh mesh, int firstVertex, int vertices, RenderType type,
                              Matrix4f modelView, Matrix4f projection, int light) {
        boolean changed = use(type);
        var shader = RenderSystem.getShader();
        buffer.bind();
        if (metrics != null) metrics.bufferBinds++;
        if (mesh != null) {
            saveConstant(mesh.storage.lightAttribute);
            saveConstant(mesh.storage.identityAttribute);
            GL30.glVertexAttribI2i(mesh.storage.lightAttribute, light & 0xffff, light >>> 16 & 0xffff);
            GL30.glVertexAttribI3i(mesh.storage.identityAttribute,
                    drawIdentity[0] & 0xffff, drawIdentity[1] & 0xffff, drawIdentity[2] & 0xffff);
        }
        boolean matricesChanged = !appliedView.equals(modelView) || !appliedProjection.equals(projection);
        if (shader != appliedShader || matricesChanged) {
            // A changed pose still uses Iris apply(), including inverse/normal matrices and custom uniforms.
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
            appliedShader = shader;
            shader.apply();
            appliedView.set(modelView);
            appliedProjection.set(projection);
            if (metrics != null) {
                metrics.applies++;
                if (matricesChanged) metrics.matrixChanges++;
            }
        }
        if (mesh != null && mesh.shared) GL11.glDrawArrays(GL11.GL_TRIANGLES, firstVertex, vertices);
        else buffer.draw();
        if (!entity) {
            appliedShader = null;
            try { shader.clear(); }
            finally { VertexBuffer.unbind(); }
        }
        return changed;
    }

    @Override public void close() {
        if (metrics != null) metrics.finishDraw();
        boolean completed = false;
        try {
            restore();
            completed = true;
        } finally {
            if (metrics != null) metrics.restored(completed);
        }
    }

    private void restore() {
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
