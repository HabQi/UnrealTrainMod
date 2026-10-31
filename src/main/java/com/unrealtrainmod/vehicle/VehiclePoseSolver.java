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
    /** Successful results retain the exact rail samples for movement protection bounds. */
    public record Result(VehiclePose pose, Failure failure, TrackVector frontTrackPosition, TrackVector rearTrackPosition) {}
    public record Range(double min, double max) {
        public boolean contains(double s) { return min <= s && s <= max; }
    }

    private VehiclePoseSolver() {}

    public static Range range(double routeLength, VehicleMotionSpec spec) {
        return range(routeLength, spec, 1);
    }

    public static Range range(double routeLength, VehicleMotionSpec spec, int direction) {
        double front = direction * spec.frontBogieOffset(), rear = direction * spec.rearBogieOffset();
        return new Range(Math.max(0, -Math.min(front, rear)),
                Math.min(routeLength, routeLength - Math.max(front, rear)));
    }

    /** Keep exact physical endpoints when adding a signed bogie offset back to a reference. */
    public static double sampleDistance(double reference, double offset, double routeLength) {
        if (offset >= 0 && reference == routeLength - offset) return routeLength;
        if (offset <= 0 && reference == -offset) return 0;
        return reference + offset;
    }

    public static Result solve(TrackPathSampler sampler, double s, VehicleMotionSpec spec) {
        return solve(sampler, s, spec, 1);
    }

    public static Result solve(TrackPathSampler sampler, double s, VehicleMotionSpec spec, int direction) {
        var range = range(sampler.getLength(), spec, direction);
        if (!range.contains(s)) return new Result(null, Failure.INSUFFICIENT_ROUTE, null, null);
        double frontS = sampleDistance(s, direction * spec.frontBogieOffset(), sampler.getLength());
        double rearS = sampleDistance(s, direction * spec.rearBogieOffset(), sampler.getLength());
        var front = sampler.sample(frontS, direction);
        var rear = sampler.sample(rearS, direction);
        var delta = VehiclePose.subtract(front.position(), rear.position());
        double horizontal = Math.hypot(delta.x(), delta.z());
        double length = Math.hypot(horizontal, delta.y());
        if (!Double.isFinite(length) || length < 1e-6 || horizontal < 1e-6) {
            return new Result(null, Failure.DEGENERATE_GEOMETRY, null, null);
        }
        float yaw = yaw(delta), pitch = pitch(delta);
        var rotation = VehiclePose.Body.rotation(yaw, pitch);
        var up = VehiclePose.rotate(rotation, new TrackVector(0, spec.trackDatum(), 0));
        double midpoint = spec.frontBogieOffset() * 0.5 + spec.rearBogieOffset() * 0.5;
        var p = new TrackVector((front.position().x() + rear.position().x()) * 0.5 - midpoint * delta.x() / length + up.x(),
                (front.position().y() + rear.position().y()) * 0.5 - midpoint * delta.y() / length + up.y(),
                (front.position().z() + rear.position().z()) * 0.5 - midpoint * delta.z() / length + up.z());
        if (!finite(p)) {
            return new Result(null, Failure.DEGENERATE_GEOMETRY, null, null);
        }
        var body = new VehiclePose.Body(p, yaw, pitch);
        var inverse = rotation.conjugate();
        var frontBogie = relative(front, body, inverse, spec.trackDatum(), direction);
        var rearBogie = relative(rear, body, inverse, spec.trackDatum(), direction);
        if (!finite(frontBogie.position()) || !finite(rearBogie.position())) {
            return new Result(null, Failure.DEGENERATE_GEOMETRY, null, null);
        }
        return new Result(new VehiclePose(body, frontBogie, rearBogie), null, front.position(), rear.position());
    }

    private static boolean finite(TrackVector p) {
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z());
    }

    private static VehiclePose.Bogie relative(TrackPose track, VehiclePose.Body body, Quaternionf inverse, double datum, int direction) {
        var p = track.position();
        var n = track.normal();
        var world = new TrackVector(p.x() + n.x() * datum, p.y() + n.y() * datum, p.z() + n.z() * datum);
        var position = VehiclePose.rotate(inverse, VehiclePose.subtract(world, body.position()));
        var tangent = track.tangent();
        var facing = new TrackVector(direction * tangent.x(), direction * tangent.y(), direction * tangent.z());
        var rotation = VehiclePose.Body.rotation(yaw(facing), pitch(facing));
        return VehiclePose.Bogie.of(position, new Quaternionf(inverse).mul(rotation).normalize());
    }

    private static float yaw(TrackVector direction) {
        return (float)Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
    }

    private static float pitch(TrackVector direction) {
        return (float)-Math.toDegrees(Math.atan2(direction.y(), Math.hypot(direction.x(), direction.z())));
    }
}
