/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.unrealtrainmod.entity.PlacedModelEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

public final class PlacedModelRenderer extends EntityRenderer<PlacedModelEntity> {
    public PlacedModelRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void render(PlacedModelEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        var model = ClientModelManager.INSTANCE.model();
        pose.pushPose();
        try {
            pose.mulPose(Axis.YP.rotationDegrees(-yaw));
            if (model == null) {
                // Keep a failed import selectable and visible instead of leaving an invisible entity.
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
                        -1.6, 0, -13.43, 1.6, 4.39, 13.43, 1, 0, 1, 1);
            } else {
                boolean opaqueOnGpu = model.gpu() != null && model.gpu().draw(pose.last(), light);
                renderBuffered(model, pose, buffers, light, opaqueOnGpu);
            }
        } finally {
            pose.popPose();
        }
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    private static void renderBuffered(ClientModelManager.LoadedModel model, PoseStack pose,
                                       MultiBufferSource buffers, int light, boolean opaqueOnGpu) {
        // Transparent geometry keeps Minecraft's buffered path. A missing shader/GPU upload also falls back here.
        for (int pass = opaqueOnGpu ? 1 : 0; pass < 2; pass++) {
            boolean translucent = pass == 1;
            for (var batch : model.batches()) {
                if (batch.translucent() != translucent) continue;
                var type = translucent ? RenderType.entityTranslucent(batch.texture())
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

    @Override public ResourceLocation getTextureLocation(PlacedModelEntity entity) { return ClientModelManager.WHITE; }
}
