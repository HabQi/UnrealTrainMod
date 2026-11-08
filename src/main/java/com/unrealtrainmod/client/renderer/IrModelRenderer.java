/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.compat.ir.IrAnimationDefinition;
import com.unrealtrainmod.compat.ir.IrModelComponent;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import com.unrealtrainmod.compat.ir.IrVehicleSnapshot;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.vehicle.VehiclePose;
import com.unrealtrainmod.vehicle.VehicleRuntimeState;
import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One immutable animation snapshot per vehicle/frame, shared by the native and Iris passes. */
public final class IrModelRenderer {
    // Values retain no entity/world reference. Model reload and world unload also clear this map.
    private static final Map<TrainEntity, RenderState> STATES = new WeakHashMap<>();
    private static long generation;
    public record Frame(double distance, IrVehicleDefinition definition, IrVehicleSnapshot controls,
                        VehicleRuntimeState runtime, float[] percentages) {}
    private static final class RenderState {
        final Travel travel = new Travel();
        long generation = -1;
        LoadedModel model;
        Frame frame;
    }

    private IrModelRenderer() {}

    public static void beginFrame() {
        generation++;
        STATES.keySet().removeIf(TrainEntity::isRemoved);
    }

    public static Frame sample(LoadedModel model, TrainEntity entity, VehiclePose vehiclePose, float partialTick) {
        RenderState cached = entity == null ? null : STATES.computeIfAbsent(entity, ignored -> new RenderState());
        if (cached != null && cached.model != null && cached.model != model) {
            cached = new RenderState();
            STATES.put(entity, cached);
        }
        if (cached != null && cached.model == model && cached.generation == generation) return cached.frame;
        var definition = model.ir();
        var controls = entity == null ? IrVehicleSnapshot.EMPTY : entity.irSnapshot();
        var runtime = entity == null ? null : entity.runtimeState();
        double time = entity == null ? 0 : entity.level().getGameTime() - 1d
                + (entity.level().tickRateManager().runsNormally() ? partialTick : 1);
        double distance = 0;
        if (cached != null) {
            var railPose = vehiclePose != null ? vehiclePose : entity.renderPose(partialTick);
            if (railPose != null) distance = cached.travel.sample(entity, railPose, partialTick);
        }
        float[] percentages = new float[definition.animations().size()];
        var binding = model.instances().getFirst().irBinding();
        for (int i = 0; i < percentages.length; i++) {
            var animation = definition.animations().get(i);
            float value = animation.value(definition, controls.values(), runtime, controls.frontCoupled(), controls.rearCoupled());
            var frames = binding.animations().get(i);
            if (frames == null) continue;
            boolean active = value >= 0.95f;
            if (animation.mode() == IrAnimationDefinition.Mode.VALUE) percentages[i] = value;
            else if (animation.mode() == IrAnimationDefinition.Mode.LOOP) {
                percentages[i] = entity != null && active ? (float)((time * animation.framesPerTick() / frames.frameCount()) % 1) : 0;
            } else {
                var clock = i < controls.clocks().size() ? controls.clocks().get(i) : null;
                boolean settled = entity == null || clock == null || clock.tick() == Long.MIN_VALUE;
                if (clock != null && entity != null) active = clock.active();
                float progress = settled ? 1 : (float)Math.clamp((time - clock.tick()) * animation.framesPerTick() / frames.frameCount(), 0, 1);
                percentages[i] = animation.mode() == IrAnimationDefinition.Mode.PLAY_FORWARD
                        ? (active ? progress : 0) : (active ? progress : 1 - progress);
            }
        }
        var frame = new Frame(distance, definition, controls, runtime, percentages);
        if (cached != null) {
            cached.model = model; cached.generation = generation; cached.frame = frame;
        }
        return frame;
    }

    /** The caller already applied the body/bogie instance transform. */
    public static void submit(ModelDrawSink sink, LoadedModel.Instance instance, Frame frame, PoseStack pose, int light) {
        var stats = sink.statistics();
        for (var part : instance.irBinding().parts().get(instance.definition().role())) {
            var component = part.definition();
            float value = 0;
            if (component != null) {
                float raw = component.kind() == IrModelComponent.Kind.GAUGE
                        ? component.readout().sample(frame.runtime(), frame.definition().maxSpeedKmh(), frame.controls().frontCoupled(), frame.controls().rearCoupled())
                        : frame.definition().value(component.controlGroup(), frame.controls().values(), frame.runtime(), frame.controls().frontCoupled(), frame.controls().rearCoupled());
                value = component.value(raw);
                if (component.hide() && value == 1) continue;
            }
            pose.pushPose();
            try {
                // Wheel motion belongs inside the bogie pose, before its animation/control chain.
                if (component != null && component.kind() == IrModelComponent.Kind.WHEEL) {
                    double circumference = Math.PI * component.diameter();
                    rotate(pose, component, (float)(360 * (frame.distance() % circumference) / circumference));
                }
                for (var animation : part.animations()) {
                    boolean looping = frame.definition().animations().get(animation.animation()).mode() == IrAnimationDefinition.Mode.LOOP;
                    var transform = animation.frames().sample(animation.track(), frame.percentages()[animation.animation()], looping);
                    pose.translate(transform.x(), transform.y(), transform.z());
                    pose.mulPose(transform.rotation());
                    // PoseStack.scale also updates inverse-transpose normals for non-uniform scales.
                    pose.scale(transform.sx(), transform.sy(), transform.sz());
                }
                if (component != null && component.kind() != IrModelComponent.Kind.WHEEL) {
                    var translation = component.translation();
                    pose.translate(translation.x() * value, translation.y() * value, translation.z() * value);
                    rotate(pose, component, component.degrees() * value);
                }
                if (stats != null) stats.irSelection(part.selection(), true, sink.lights());
                sink.submitGeometry(instance.model(), part.selection(), pose, light);
            } finally {
                pose.popPose();
            }
        }
    }

    private static void rotate(PoseStack pose, IrModelComponent component, float degrees) {
        if (degrees == 0) return;
        var pivot = component.pivot();
        var axis = component.axis();
        pose.translate(pivot.x(), pivot.y(), pivot.z());
        pose.mulPose(new Quaternionf().rotationAxis((float)Math.toRadians(degrees), (float)axis.x(), (float)axis.y(), (float)axis.z()));
        pose.translate(-pivot.x(), -pivot.y(), -pivot.z());
    }

    public static void clear() { STATES.clear(); }

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
