/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.wiring.WiringDefinition;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Straight and parabolic spans, with exact endpoints and model-local +Y as the repeat axis. */
public final class WireShape {
    @FunctionalInterface public interface Filter { boolean render(int count, int index); }
    private WireShape() {}
    public static List<WiringGeometry.Draw> build(WiringDefinition definition, List<WiringGeometry.Batch> batches,
            Vec3 target, boolean sag, PoseStack pose, int light, Filter filter) {
        double horizontal = Math.hypot(target.x, target.z), length = target.length();
        if (length < 1e-6) return List.of();
        double alpha = sag && horizontal > 1e-6
                ? definition.deflection() * horizontal / length / Math.pow(1 + definition.lengthCoefficient(), horizontal) : 0;
        double arc = 0; Vec3 previous = Vec3.ZERO;
        for (int i = 1; i <= 32; i++) {
            var point = point(target, alpha, horizontal, i / 32.0); arc += point.distanceTo(previous); previous = point;
        }
        double requested = Math.ceil(arc / definition.sectionLength());
        if (!Double.isFinite(requested) || requested > 16384) throw new IllegalArgumentException("Wire exceeds 16384 model sections");
        int count = Math.max(1, (int)requested); var output = new ArrayList<WiringGeometry.Draw>();
        for (int i = 0; i < count; i++) {
            if (!filter.render(count, i)) continue;
            var start = point(target, alpha, horizontal, (double)i / count);
            var end = point(target, alpha, horizontal, (double)(i + 1) / count);
            var delta = end.subtract(start);
            double yaw = Math.atan2(delta.x, delta.z), pitch = Math.atan2(delta.y, Math.hypot(delta.x, delta.z));
            boolean curved = sag && horizontal > 1e-6 && alpha > 0;
            var rotation = new Quaternionf().rotateY((float)(yaw + (curved ? 0 : Math.PI)))
                    .rotateX((float)(curved ? Math.PI / 2 - pitch : pitch - Math.PI / 2));
            pose.pushPose();
            try {
                pose.translate(start.x, start.y + definition.yOffset(), start.z);
                pose.mulPose(rotation);
                pose.scale(1, (float)(delta.length() / definition.sectionLength()), 1);
                for (var batch : batches) output.add(new WiringGeometry.Draw(batch,
                        new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), light));
            } finally { pose.popPose(); }
        }
        return List.copyOf(output);
    }
    private static Vec3 point(Vec3 target, double alpha, double horizontal, double t) {
        return target.scale(t).add(0, alpha * horizontal * horizontal * t * (t - 1), 0);
    }
}
