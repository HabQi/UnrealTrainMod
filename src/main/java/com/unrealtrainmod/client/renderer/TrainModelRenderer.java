/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.ClientModelManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** World submissions are deferred so all visible trains share the same opaque and translucent stages. */
public final class TrainModelRenderer {
    private static final ModelRenderQueue WORLD = new ModelRenderQueue();

    private TrainModelRenderer() {}

    /** The source remains in the signature for callers; geometry is submitted to the world scene queue. */
    public static void render(ClientModelManager.LoadedModel model, PoseStack pose,
                              MultiBufferSource buffers, int light) {
        WORLD.submit(model, pose, light);
    }

    /** Exact-name matches include hidden parts; submission never changes source visibility or geometry. */
    public static boolean renderPart(ClientModelManager.LoadedInstance instance, String name, PoseStack pose,
                                     MultiBufferSource buffers, int light) {
        return submitPart(WORLD, instance, name, pose, light);
    }

    /** Independent scenes can use named-part selections without leaking into the world queue. */
    public static boolean submitPart(ModelRenderQueue queue, ClientModelManager.LoadedInstance instance,
                                     String name, PoseStack pose, int light) {
        var selection = instance.model().partsByName().get(name);
        if (selection == null) return false;
        queue.submit(instance, selection, pose, light);
        return true;
    }

    public static void onFramePre(RenderFrameEvent.Pre event) { clearWorld(); }

    public static void onRenderStage(RenderLevelStageEvent event) {
        var stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY || stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            clearWorld();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            WORLD.drawOpaque();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            // Keep the event's active target, including the fabulous particles target.
            WORLD.drawTranslucent();
        }
    }

    public static void clearWorld() { WORLD.clear(); }
    public static void closeWorld() { WORLD.close(); }
}
