/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.path.TrackPathSampler;
import org.joml.Quaternionf;

/** Two samples on one directed route; movement speed never affects model orientation. */
public final class VehiclePoseSolver {
    public enum Failure { INSUFFICIENT_ROUTE, DEGENERATE_GEOMETRY }
    public record Result(VehiclePose pose, Failure failure) {}
    public record Range(double min, double max) {
        public boolean contains(double s) { return min <= s && s <= max; }
    }

    private VehiclePoseSolver() {}

    public static Range range(double routeLength, VehicleMotionSpec spec) {
        return new Range(Math.max(0, -spec.rearBogieOffset()),
                Math.min(routeLength, routeLength - spec.frontBogieOffset()));
    }

    public static Result solve(TrackPathSampler sampler, double s, VehicleMotionSpec spec) {
        var range = range(sampler.getLength(), spec);
        if (!range.contains(s)) return new Result(null, Failure.INSUFFICIENT_ROUTE);
        // Form exact endpoint coordinates without rounding (L - offset) + offset past L.
        double frontS = s == range.max && spec.frontBogieOffset() >= 0
                ? sampler.getLength() : s + spec.frontBogieOffset();
        double rearS = s == range.min && spec.rearBogieOffset() <= 0 ? 0 : s + spec.rearBogieOffset();
        var front = sampler.sample(frontS);
        var rear = sampler.sample(rearS);
        var delta = VehiclePose.subtract(front.position(), rear.position());
        double horizontal = Math.hypot(delta.x(), delta.z());
        double length = Math.hypot(horizontal, delta.y());
        if (!Double.isFinite(length) || length < 1e-6 || horizontal < 1e-6) {
            return new Result(null, Failure.DEGENERATE_GEOMETRY);
        }
        float yaw = yaw(delta), pitch = pitch(delta);
        var rotation = VehiclePose.Body.rotation(yaw, pitch);
        var up = VehiclePose.rotate(rotation, new TrackVector(0, spec.trackDatum(), 0));
        double midpoint = spec.frontBogieOffset() * 0.5 + spec.rearBogieOffset() * 0.5;
        var p = new TrackVector((front.position().x() + rear.position().x()) * 0.5 - midpoint * delta.x() / length + up.x(),
                (front.position().y() + rear.position().y()) * 0.5 - midpoint * delta.y() / length + up.y(),
                (front.position().z() + rear.position().z()) * 0.5 - midpoint * delta.z() / length + up.z());
        if (!finite(p)) {
            return new Result(null, Failure.DEGENERATE_GEOMETRY);
        }
        var body = new VehiclePose.Body(p, yaw, pitch);
        var inverse = rotation.conjugate();
        var frontBogie = relative(front, body, inverse, spec.trackDatum());
        var rearBogie = relative(rear, body, inverse, spec.trackDatum());
        if (!finite(frontBogie.position()) || !finite(rearBogie.position())) {
            return new Result(null, Failure.DEGENERATE_GEOMETRY);
        }
        return new Result(new VehiclePose(body, frontBogie, rearBogie), null);
    }

    private static boolean finite(TrackVector p) {
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z());
    }

    private static VehiclePose.Bogie relative(TrackPose track, VehiclePose.Body body, Quaternionf inverse, double datum) {
        var p = track.position();
        var n = track.normal();
        var world = new TrackVector(p.x() + n.x() * datum, p.y() + n.y() * datum, p.z() + n.z() * datum);
        var position = VehiclePose.rotate(inverse, VehiclePose.subtract(world, body.position()));
        var rotation = VehiclePose.Body.rotation(yaw(track.tangent()), pitch(track.tangent()));
        return VehiclePose.Bogie.of(position, new Quaternionf(inverse).mul(rotation).normalize());
    }

    private static float yaw(TrackVector direction) {
        return (float)Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
    }

    private static float pitch(TrackVector direction) {
        return (float)-Math.toDegrees(Math.atan2(direction.y(), Math.hypot(direction.x(), direction.z())));
    }
}
