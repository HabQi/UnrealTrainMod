/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/** The GUI quad is already framed; all other contexts retain the authored item transform. */
public final class CachedObjModel extends BakedModelWrapper<BakedModel> {
    private final ItemGeometry geometry;

    CachedObjModel(BakedModel original, ItemGeometry geometry) {
        super(original);
        this.geometry = geometry;
    }

    BakedModel original() { return originalModel; }
    ItemGeometry geometry() { return geometry; }

    @Override public boolean isCustomRenderer() { return true; }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
        if (context != ItemDisplayContext.GUI) originalModel.applyTransform(context, pose, leftHand);
        return this;
    }
}
