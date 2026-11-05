/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.unrealtrainmod.model.IndexedMesh;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;

/** Transforms commands on the CPU while retaining the actual authored surface normal. */
public final class IrisVertices {
    private final Vector3f position = new Vector3f(), normal = new Vector3f();

    public void vertex(VertexConsumer target, IndexedMesh mesh, int index, PoseStack.Pose pose, int light, boolean opaque) {
        int offset = index * 8;
        float[] v = mesh.vertices();
        pose.pose().transformPosition(position.set(v[offset], v[offset + 1], v[offset + 2]));
        pose.normal().transform(normal.set(v[offset + 5], v[offset + 6], v[offset + 7])).normalize();
        int color = mesh.colors()[index];
        target.addVertex(position.x, position.y, position.z).setColor(opaque ? color | 0xff000000 : color)
                .setUv(v[offset + 3], v[offset + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(normal.x, normal.y, normal.z);
    }
}
