/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.StaticModelShader;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import net.minecraft.resources.ResourceLocation;

/** firstIndex/indexCount address mesh indices, not bytes. A null mode retains the legacy package policy. */
public record RenderBatch(IndexedMesh mesh, int firstIndex, int indexCount, ResourceLocation texture, RenderCategory category,
                          MaterialRenderMode mode, boolean doubleSided, float[] centers) {
    public RenderBatch(IndexedMesh mesh, ResourceLocation texture, RenderCategory category,
                       MaterialRenderMode mode, boolean doubleSided) {
        this(mesh, 0, mesh.indices().length, texture, category, mode, doubleSided);
    }

    public RenderBatch(IndexedMesh mesh, ResourceLocation texture, RenderCategory category,
                       MaterialRenderMode mode, boolean doubleSided, float[] centers) {
        this(mesh, 0, mesh.indices().length, texture, category, mode, doubleSided, centers);
    }

    public RenderBatch(IndexedMesh mesh, int firstIndex, int indexCount, ResourceLocation texture,
                       RenderCategory category, MaterialRenderMode mode, boolean doubleSided) {
        this(mesh, firstIndex, indexCount, texture, category, mode, doubleSided,
                triangleCenters(mesh, firstIndex, indexCount, category));
    }

    public int alphaPass(boolean blended) {
        if (mode == MaterialRenderMode.OPAQUE) return StaticModelShader.FORCE_OPAQUE;
        if (mode == MaterialRenderMode.CUTOUT) return StaticModelShader.FORCE_CUTOUT;
        if (mode == MaterialRenderMode.TRANSLUCENT) return StaticModelShader.FORCE_TRANSLUCENT;
        if (blended) return StaticModelShader.BLENDED;
        return category == RenderCategory.MIXED ? StaticModelShader.MIXED_OPAQUE : StaticModelShader.LEGACY_CUTOUT;
    }

    private static float[] triangleCenters(IndexedMesh mesh, int firstIndex, int indexCount, RenderCategory category) {
        if (category == RenderCategory.CUTOUT) return new float[0];
        int[] indices = mesh.indices();
        float[] vertices = mesh.vertices(), centers = new float[indexCount];
        for (int i = 0; i < indexCount; i += 3) {
            int a = indices[firstIndex + i] * 8, b = indices[firstIndex + i + 1] * 8, c = indices[firstIndex + i + 2] * 8;
            for (int axis = 0; axis < 3; axis++) {
                centers[i + axis] = (vertices[a + axis] + vertices[b + axis] + vertices[c + axis]) / 3;
            }
        }
        return centers;
    }
}
