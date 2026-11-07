/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.compat.ir.IrModelComponent;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** IR visual state is sampled independently of the drawing backend and never changes source vertices. */
public final class IrModelRenderer {
    // Values deliberately retain no entity/world reference; removed entities can leave the weak map naturally.
    private static final Map<TrainEntity, Travel> TRAVEL = new WeakHashMap<>();
    public record Frame(double distance, Map<String, Float> controls) {}

    private IrModelRenderer() {}

    public static Frame sample(LoadedModel model, TrainEntity entity, VehiclePose vehiclePose, float partialTick) {
        var controls = new HashMap<>(model.irControlDefaults());
        double distance = 0;
        if (entity != null) {
            var state = entity.runtimeState();
            for (var instance : model.instances()) {
                for (var part : instance.model().irParts().getOrDefault(instance.definition().role(), List.of())) {
                    var definition = part.definition();
                    Float position = switch (definition.kind()) {
                        case THROTTLE -> (float)state.throttle();
                        case TRAIN_BRAKE -> state.emergencyBrake() ? 1f : (float)state.trainBrake();
                        case REVERSER -> (state.reverser() + 1) * 0.5f;
                        default -> null;
                    };
                    if (position != null) controls.put(definition.controlGroup(), position);
                }
            }
            var railPose = vehiclePose != null ? vehiclePose : entity.renderPose(partialTick);
            if (railPose != null) {
                distance = TRAVEL.computeIfAbsent(entity, ignored -> new Travel()).sample(entity, railPose, partialTick);
            }
        }
        return new Frame(distance, controls);
    }

    /** The caller already applied the body/bogie instance transform. */
    public static void submit(ModelDrawSink sink, LoadedModel.Instance instance, Frame frame, PoseStack pose, int light) {
        for (var part : instance.model().irParts().getOrDefault(instance.definition().role(), List.of())) {
            var definition = part.definition();
            float value = definition.value(frame.controls().getOrDefault(definition.controlGroup(), definition.defaultPosition()));
            if (definition.hide() && value == 1) continue;
            float degrees = definition.degrees() * value;
            if (definition.kind() == IrModelComponent.Kind.WHEEL) {
                double circumference = Math.PI * definition.diameter();
                degrees = (float)(360 * (frame.distance() % circumference) / circumference);
            }
            var translation = definition.translation();
            pose.pushPose();
            try {
                pose.translate(translation.x() * value, translation.y() * value, translation.z() * value);
                if (degrees != 0) {
                    var pivot = definition.pivot();
                    var axis = definition.axis();
                    pose.translate(pivot.x(), pivot.y(), pivot.z());
                    pose.mulPose(new Quaternionf().rotationAxis((float)Math.toRadians(degrees),
                            (float)axis.x(), (float)axis.y(), (float)axis.z()));
                    pose.translate(-pivot.x(), -pivot.y(), -pivot.z());
                }
                sink.submitGeometry(instance.model(), part.selection(), pose, light);
            } finally {
                pose.popPose();
            }
        }
    }

    public static void clear() { TRAVEL.clear(); }

    private static final class Travel {
        private long tick = Long.MIN_VALUE, revision = Long.MIN_VALUE;
        private float partial = -1;
        private boolean positioned;
        private double x, y, z, distance;

        double sample(TrainEntity entity, VehiclePose pose, float partialTick) {
            long now = entity.level().getGameTime();
            long generation = entity.poseRevision();
            if (revision == generation && tick == now && partial == partialTick) return distance;
            if (revision != generation) positioned = false;
            var p = pose.body().position();
            if (positioned) {
                double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
                if (dx * dx + dy * dy + dz * dz < 32 * 32) {
                    var forward = new Vector3f(0, 0, 1).rotate(pose.body().rotation());
                    double movement = dx * forward.x + dy * forward.y + dz * forward.z;
                    if (Math.abs(movement) > 1e-7) distance += movement;
                }
            }
            x = p.x(); y = p.y(); z = p.z();
            positioned = true;
            revision = generation; tick = now; partial = partialTick;
            return distance;
        }
    }
}
