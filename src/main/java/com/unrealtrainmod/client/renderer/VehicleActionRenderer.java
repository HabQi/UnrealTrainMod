/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.compat.rtm.script.ScriptDiagnostics;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.vehicle.VehicleAction;
import com.unrealtrainmod.vehicle.VehicleActions;
import com.unrealtrainmod.vehicle.VehicleDoor;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import java.util.List;
import org.joml.Quaternionf;

/**
 * Draws RTM {@code door_left}/{@code door_right} actions on the body. Progress comes from the vehicle
 * instance, while the action definition and part selections are shared by the model source.
 */
public final class VehicleActionRenderer {
    private VehicleActionRenderer() {}

    /**
     * @param scriptOwnsBody the body renderer took over this model instance and already drew the door objects,
     *                       so the configured actions must not submit them a second time
     */
    public static void submit(ModelDrawSink queue, LoadedModel.Instance instance, TrainEntity entity,
                              float partialTick, PoseStack pose, int light, boolean scriptOwnsBody) {
        var definition = instance.definition();
        if (definition.role() != VehiclePartRole.BODY) return;
        var actions = definition.actions();
        if (actions.isEmpty()) return;
        var source = instance.model();
        String model = definition.source().model().toString();
        if (scriptOwnsBody) {
            // The script draws the whole body, including any parts that also appear in door actions.
            ScriptDiagnostics.actionIgnoredForScript(model);
            return;
        }
        submitSide(queue, source, model, actions.doors(VehicleDoor.LEFT),
                movement(entity, VehicleDoor.LEFT, partialTick), pose, light);
        submitSide(queue, source, model, actions.doors(VehicleDoor.RIGHT),
                movement(entity, VehicleDoor.RIGHT, partialTick), pose, light);
    }

    private static float movement(TrainEntity entity, VehicleDoor side, float partialTick) {
        return entity == null ? 0.0f : entity.doorMovement(side, partialTick);
    }

    private static void submitSide(ModelDrawSink queue, LoadedModel.Source source, String model, List<VehicleAction> actions,
                                   float progress, PoseStack pose, int light) {
        float move = sigmoid(progress);
        for (var action : actions) submitAction(queue, source, model, action, move, pose, light);
    }

    private static void submitAction(ModelDrawSink queue, LoadedModel.Source source, String model, VehicleAction action,
                                     float move, PoseStack pose, int light) {
        var pivot = action.pivot();
        pose.pushPose();
        try {
            pose.translate(pivot.x(), pivot.y(), pivot.z());
            for (var transform : action.transforms()) {
                if (transform instanceof VehicleAction.Translate value) {
                    pose.translate(value.x() * move, value.y() * move, value.z() * move);
                } else if (transform instanceof VehicleAction.Rotate value) {
                    rotate(pose, value.angle() * move, value.x(), value.y(), value.z());
                }
            }
            pose.translate(-pivot.x(), -pivot.y(), -pivot.z());
            for (String name : action.objects()) {
                var selection = source.actionParts().get(VehicleActions.key(name));
                if (selection != null) queue.submitGeometry(source, selection, pose, light);
                else ScriptDiagnostics.missingActionPart(model, name);
            }
            // Child actions inherit the parent pivot and movement, matching the legacy renderer.
            for (var child : action.children()) submitAction(queue, source, model, child, move, pose, light);
        } finally {
            pose.popPose();
        }
    }

    /** RTM applies one eased progress value to the root node and all of its children. */
    static float sigmoid(float progress) {
        if (progress <= 0.0f) return 0.0f;
        if (progress >= 1.0f) return 1.0f;
        float scaled = (progress - 0.5f) * 5.0f;
        float curve = (float)((double)scaled / Math.sqrt(1.0 + (double)scaled * scaled));
        return (curve + 1.0f) * 0.5f;
    }

    private static void rotate(PoseStack pose, float angle, float x, float y, float z) {
        float scale = (float)(1.0 / Math.sqrt((double)x * x + (double)y * y + (double)z * z));
        pose.mulPose(new Quaternionf().rotateAxis((float)Math.toRadians(angle), x * scale, y * scale, z * scale));
    }
}
