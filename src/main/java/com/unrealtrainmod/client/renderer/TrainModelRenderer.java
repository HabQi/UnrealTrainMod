/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.compat.rtm.script.VehicleScriptManager;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.vehicle.VehiclePose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** World submissions are deferred so all visible trains share the same opaque and translucent stages. */
public final class TrainModelRenderer {
    private static final ModelRenderQueue WORLD = new ModelRenderQueue();

    private TrainModelRenderer() {}

    /** Running vehicle: each source owns its part transform, then a script or the static geometry. */
    public static void render(LoadedModel model, VehiclePose vehiclePose, TrainEntity entity, float partialTick,
                              PoseStack pose, int light) {
        submit(WORLD, model, vehiclePose, entity, partialTick, pose, light);
    }

    /** Placed/static vehicle with no rail pose; scripts run with an entity-less preview context. */
    public static void renderPlaced(LoadedModel model, PoseStack pose, int light) {
        submit(WORLD, model, null, null, 0.0f, pose, light);
    }

    /** GUI preview and thumbnail scene; uses its own queue so it never pollutes the world. */
    public static void submitPreview(ModelRenderQueue queue, LoadedModel model, PoseStack pose, int light) {
        submit(queue, model, null, null, 0.0f, pose, light);
    }

    private static void submit(ModelRenderQueue queue, LoadedModel model, VehiclePose vehiclePose,
                               TrainEntity entity, float partialTick, PoseStack pose, int light) {
        for (var instance : model.instances()) {
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
                if (!VehicleScriptManager.render(queue, entity, vehiclePose, instance, pose, light, partialTick)) {
                    // The caller already applied the instance offset, so submit source-local geometry directly.
                    queue.submitGeometry(instance.model(), instance.visibleParts(), pose, light);
                    VehicleActionRenderer.submit(queue, instance, entity, partialTick, pose, light);
                }
            } finally {
                pose.popPose();
            }
        }
    }

    /** Exact-name matches include hidden parts; submission never changes source visibility or geometry. */
    public static boolean renderPart(LoadedModel.Instance instance, String name, PoseStack pose,
                                     MultiBufferSource buffers, int light) {
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
