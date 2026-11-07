/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.renderer.RenderBatch;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

/** Immediate static-item draws in the caller's current entity or hand target. */
public final class IrisItemRenderer {
    public static final int UNSUPPORTED_BUFFER = -1, UNSUPPORTED_POSE = -2, CACHE_UNAVAILABLE = -3;

    private IrisItemRenderer() {}

    /** Returns the draw count or a named negative fallback reason before drawing any geometry. */
    public static int render(List<RenderBatch> batches, PoseStack.Pose pose, MultiBufferSource buffers, int light) {
        var source = IrisCompat.itemBuffer(buffers);
        if (source == null) return UNSUPPORTED_BUFFER;
        if (!IrisGeometryCache.supportsPose(pose.pose())) return UNSUPPORTED_POSE;
        IrisCompat.flushItemBuffer(source);
        try (var scope = IrisCompat.handPass() ? IrisDrawScope.hand() : IrisDrawScope.entity()) {
            var meshes = new IrisGeometryCache.Mesh[batches.size()];
            for (int i = 0; i < batches.size(); i++) {
                var batch = batches.get(i);
                meshes[i] = IrisGeometryCache.requestItem(batch.mesh(), batch.firstIndex(), batch.indexCount());
                if (meshes[i] == null) return CACHE_UNAVAILABLE;
            }
            var view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.pose());
            var projection = RenderSystem.getProjectionMatrix();
            for (int i = 0; i < batches.size(); i++) {
                var batch = batches.get(i);
                scope.drawCached(meshes[i], IrisRenderTypes.get(batch.texture(), batch.doubleSided(), false, false),
                        view, projection, light);
            }
            return batches.size();
        }
    }
}
