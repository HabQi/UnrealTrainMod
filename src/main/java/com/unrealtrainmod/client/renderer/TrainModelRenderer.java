/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.ClientModelManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Shared submission path for world entities and the independent GUI preview. */
public final class TrainModelRenderer {
    private TrainModelRenderer() {}

    public static void render(ClientModelManager.LoadedModel model, PoseStack pose,
                              MultiBufferSource buffers, int light) {
        // Instance transforms belong to assembly, never to the source vertices.
        for (var instance : model.instances()) {
            pose.pushPose();
            try {
                var offset = instance.offset();
                pose.translate(offset.x(), offset.y(), offset.z());
                var part = instance.model();
                boolean opaqueOnGpu = part.gpu() != null && part.gpu().draw(pose.last(), light);
                renderBuffered(part, pose, buffers, light, opaqueOnGpu);
            } finally {
                pose.popPose();
            }
        }
    }

    private static void renderBuffered(ClientModelManager.LoadedPart model, PoseStack pose,
                                       MultiBufferSource buffers, int light, boolean opaqueOnGpu) {
        // Transparent geometry keeps Minecraft's buffered path. A missing shader/GPU upload also falls back here.
        for (int pass = opaqueOnGpu ? 1 : 0; pass < 2; pass++) {
            boolean translucent = pass == 1;
            for (var batch : model.batches()) {
                if (batch.translucent() != translucent) continue;
                var type = translucent
                        ? (batch.doubleSided() ? RenderType.entityTranslucent(batch.texture()) : RenderType.entityTranslucentCull(batch.texture()))
                        : batch.doubleSided() ? RenderType.entityCutoutNoCull(batch.texture()) : RenderType.entityCutout(batch.texture());
                var consumer = buffers.getBuffer(type);
                var mesh = batch.mesh();
                float[] vertices = mesh.vertices();
                int[] colors = mesh.colors();
                int[] indices = mesh.indices();
                var m = pose.last().pose();
                var n = pose.last().normal();
                // Expand indexed triangles to (a,b,c,c) only when writing Minecraft's QUADS buffer.
                for (int corner = 0; corner < indices.length; corner++) {
                    int v = indices[corner], i = v * 8;
                    float x = vertices[i], y = vertices[i + 1], z = vertices[i + 2];
                    float nx = vertices[i + 5], ny = vertices[i + 6], nz = vertices[i + 7];
                    float px = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
                    float py = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
                    float pz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
                    float pnx = n.m00() * nx + n.m10() * ny + n.m20() * nz;
                    float pny = n.m01() * nx + n.m11() * ny + n.m21() * nz;
                    float pnz = n.m02() * nx + n.m12() * ny + n.m22() * nz;
                    // Reuse c's transformed attributes for the repeated fourth corner.
                    int copies = corner % 3 == 2 ? 2 : 1;
                    for (int copy = 0; copy < copies; copy++) {
                        consumer.addVertex(px, py, pz, colors[v], vertices[i + 3], vertices[i + 4],
                                OverlayTexture.NO_OVERLAY, light, pnx, pny, pnz);
                    }
                }
            }
        }
    }
}
