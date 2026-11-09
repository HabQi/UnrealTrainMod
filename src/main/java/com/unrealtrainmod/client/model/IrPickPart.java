/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.compat.ir.IrControlDefinition;
import net.minecraft.world.phys.AABB;

/** Source geometry and its render chain; non-controls participate in occlusion too. */
public record IrPickPart(ModelPart geometry, AABB bounds, IrModelPart motion,
                         String group, IrControlDefinition.Type type) {
    public static AABB bounds(ModelPart part) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (var batch : part.batches()) {
            var mesh = batch.mesh();
            var vertices = mesh.vertices();
            for (int i = batch.firstIndex(); i < batch.firstIndex() + batch.indexCount(); i++) {
                int p = mesh.indices()[i] * 8;
                minX = Math.min(minX, vertices[p]); maxX = Math.max(maxX, vertices[p]);
                minY = Math.min(minY, vertices[p + 1]); maxY = Math.max(maxY, vertices[p + 1]);
                minZ = Math.min(minZ, vertices[p + 2]); maxZ = Math.max(maxZ, vertices[p + 2]);
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.001);
    }
}
