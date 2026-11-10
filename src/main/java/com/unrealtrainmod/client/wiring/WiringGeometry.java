/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.model.IndexedMesh;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Retained local geometry and transforms; rendering uses the shared GPU cache. */
public final class WiringGeometry {
    public record Material(ResourceLocation texture, boolean translucent, boolean doubleSided, boolean light) {
        public int pass() { return light ? 1 : translucent ? 2 : 0; }
    }
    public record Batch(String part, Material material, IndexedMesh mesh, boolean dynamic) {
        public Batch(String part, Material material, IndexedMesh mesh) { this(part, material, mesh, false); }
    }
    public record Draw(Batch batch, Matrix4f pose, Matrix3f normal, int light) {}
    private WiringGeometry() {}
    public static void render(List<Draw> draws, PoseStack pose, MultiBufferSource buffers, int light) {
        WiringRenderCache.render(draws, pose, buffers, light, false, false);
    }
    public static List<Draw> identity(List<Batch> batches) {
        return batches.stream().map(b -> new Draw(b, new Matrix4f(), new Matrix3f(), -1)).toList();
    }
}
