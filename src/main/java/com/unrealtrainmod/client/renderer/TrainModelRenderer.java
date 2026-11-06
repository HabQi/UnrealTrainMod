/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.instancing.InstancingStats;
import com.unrealtrainmod.compat.rtm.script.VehicleScriptManager;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.vehicle.VehiclePose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** World submissions are deferred so all visible trains share the same opaque and translucent stages. */
public final class TrainModelRenderer {
    private static final ModelRenderQueue WORLD = new ModelRenderQueue();

    private TrainModelRenderer() {}

    public static InstancingStats worldStatistics() { return WORLD.statistics(); }
    public static void resetStatistics() { WORLD.resetStatistics(); }

    /** Running or placed world vehicle: each source owns its part transform, then a script or the static geometry. */
    public static void render(LoadedModel model, VehiclePose vehiclePose, TrainEntity entity, float partialTick,
                              PoseStack pose, int light) {
        submit(WORLD, model, vehiclePose, entity, partialTick, pose, light);
    }

    /** Static display model with no entity and no rail pose; scripts run in the entity-less preview context. */
    public static void renderPlaced(LoadedModel model, PoseStack pose, int light) {
        submit(WORLD, model, null, null, 0.0f, pose, light);
    }

    /** GUI preview and thumbnail scene; uses its own queue so it never pollutes the world. */
    public static void submitPreview(ModelRenderQueue queue, LoadedModel model, PoseStack pose, int light) {
        submit(queue, model, null, null, 0.0f, pose, light);
    }

    public static void submit(ModelDrawSink queue, LoadedModel model, VehiclePose vehiclePose,
                               TrainEntity entity, float partialTick, PoseStack pose, int light) {
        var stats = queue.statistics();
        long submitStart = stats == null ? 0L : System.nanoTime();
        int previousLights = queue.lights();
        queue.lights(entity == null ? 0 : entity.lights());
        try {
            for (var instance : model.instances()) {
                long poseStart = stats == null ? 0L : System.nanoTime();
                pose.pushPose();
                try {
                    if (vehiclePose != null) {
                        var transform = vehiclePose.partTransform(instance.definition());
                        var p = transform.position();
                        pose.translate(p.x(), p.y(), p.z());
                        pose.mulPose(transform.rotation());
                    } else {
                        var offset = instance.offset();
                        pose.translate(offset.x(), offset.y(), offset.z());
                        if (instance.definition().reversedBogie()) pose.mulPose(Axis.YP.rotationDegrees(180));
                    }
                    if (stats != null) stats.addPose(System.nanoTime() - poseStart);
                    long scriptStart = stats != null && instance.definition().source().rendererPath() != null ? System.nanoTime() : 0L;
                    boolean scripted;
                    try {
                        scripted = VehicleScriptManager.render(queue, entity, vehiclePose, instance, pose, light, partialTick);
                    } finally {
                        if (scriptStart != 0L) stats.addScript(System.nanoTime() - scriptStart);
                    }
                    if (!scripted) {
                        // The caller already applied the instance offset, so submit source-local geometry directly.
                        queue.submitGeometry(instance.model(), instance.visibleParts(), pose, light);
                        VehicleActionRenderer.submit(queue, instance, entity, partialTick, pose, light, false);
                    } else {
                        // A working body script owns every body part, including any part named by a door action.
                        VehicleActionRenderer.submit(queue, instance, entity, partialTick, pose, light, true);
                    }
                } finally {
                    pose.popPose();
                }
            }
        } finally {
            queue.lights(previousLights);
            if (stats != null) stats.addSubmission(System.nanoTime() - submitStart);
        }
    }

    /** Exact-name matches include hidden parts; submission never changes source visibility or geometry. */
    public static boolean renderPart(LoadedModel.Instance instance, String name, PoseStack pose,
                                     MultiBufferSource buffers, int light) {
        if (com.unrealtrainmod.client.compat.iris.IrisCompat.active())
            return com.unrealtrainmod.client.compat.iris.IrisModelRenderer.renderPart(instance, name, pose, buffers, light);
        return submitPart(WORLD, instance, name, pose, light);
    }

    /** Independent scenes can use named-part selections without leaking into the world queue. */
    public static boolean submitPart(ModelRenderQueue queue, LoadedModel.Instance instance,
                                     String name, PoseStack pose, int light) {
        var selection = instance.model().partsByName().get(name);
        if (selection == null) return false;
        queue.submit(instance, selection, pose, light);
        return true;
    }

    public static void onFramePre(RenderFrameEvent.Pre event) {
        clearWorld();
        com.unrealtrainmod.client.compat.iris.IrisModelRenderer.beginFrame();
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (com.unrealtrainmod.client.compat.iris.IrisCompat.active()) return;
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
    public static void closeWorld() {
        WORLD.close();
        com.unrealtrainmod.client.compat.iris.IrisModelRenderer.clear();
        com.unrealtrainmod.client.texture.MaterialTexture.releasePbr();
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) closeWorld();
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { closeWorld(); }
}
