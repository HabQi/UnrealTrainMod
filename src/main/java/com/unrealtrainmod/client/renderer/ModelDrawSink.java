/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.model.PartSelection;
import com.unrealtrainmod.client.renderer.instancing.InstancingStats;

/** Model commands are independent of the view and the eventual drawing backend. */
public interface ModelDrawSink {
    void submitGeometry(LoadedModel.Source source, PartSelection selection, PoseStack.Pose pose,
                        int light, boolean directionalLighting);

    default InstancingStats statistics() { return null; }

    default void submitGeometry(LoadedModel.Source source, PartSelection selection, PoseStack pose, int light) {
        submitGeometry(source, selection, pose.last(), light, true);
    }

    default void submit(LoadedModel.Instance instance, PartSelection selection, PoseStack pose, int light) {
        pose.pushPose();
        try {
            var offset = instance.offset();
            pose.translate(offset.x(), offset.y(), offset.z());
            if (instance.definition().reversedBogie()) pose.mulPose(Axis.YP.rotationDegrees(180));
            submitGeometry(instance.model(), selection, pose, light);
        } finally {
            pose.popPose();
        }
    }
}
