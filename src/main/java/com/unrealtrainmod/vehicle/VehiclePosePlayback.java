/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.vehicle.VehiclePoseSnapshot.Motion;
import java.util.ArrayList;
import java.util.TreeMap;
import org.joml.Quaternionf;

/** Timestamped playback with bounded prediction and continuous body position/velocity correction. */
public final class VehiclePosePlayback {
    private static final double PREDICTION_TICKS = 2;
    private static final double CORRECTION_TICKS = 2;
    private static final int HISTORY_SIZE = 32;

    /** Shared by all tracked vehicles in one client level; packet arrival never restarts playback. */
    public static final class Clock {
        private final TreeMap<Long, Double> observations = new TreeMap<>();
        private long localTick, passiveTick;
        private double playbackTick, rate = 1;
        private boolean initialized, live;

        public void observe(long serverTick, float partialTick, boolean predicting) {
            if (!predicting) {
                // Parked tracked data can be arbitrarily old. Let explicit static moves settle,
                // but do not let their publication dates set the live world's time offset.
                if (!live) {
                    double next = initialized ? Math.max(time(partialTick), serverTick - 1.0) : serverTick;
                    playbackTick = next - partialTick;
                    passiveTick = Math.max(passiveTick, serverTick);
                    initialized = true;
                }
                return;
            }
            if (!live) {
                double next = initialized ? Math.max(time(partialTick), serverTick - 1.0) : serverTick - 1.0;
                playbackTick = next - partialTick;
                initialized = true;
                live = true;
            }
            observations.merge(serverTick, serverTick - (localTick + (double)partialTick), Math::max);
            if (observations.size() > HISTORY_SIZE) observations.pollFirstEntry();
        }

        public void tick() {
            localTick++;
            if (!initialized) return;
            if (!live) {
                playbackTick = Math.min(playbackTick + 1, passiveTick + CORRECTION_TICKS);
                return;
            }
            playbackTick += rate;
            double offset = observations.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            double error = localTick + offset - 1 - playbackTick;
            rate = 1 + Math.clamp(error, -0.05, 0.05);
        }

        public double time(float partialTick) {
            double time = playbackTick + rate * partialTick;
            return live ? time : Math.min(time, passiveTick + CORRECTION_TICKS);
        }
    }

    private record State(VehiclePose pose, Motion velocity) {}
    private final ArrayList<VehiclePoseSnapshot> samples = new ArrayList<>();
    private double correctionTime;
    private Motion error = Motion.ZERO, velocityError = Motion.ZERO;
    private VehiclePose.Bogie frontError, rearError;

    public boolean accepts(VehiclePoseSnapshot incoming) {
        if (samples.isEmpty()) return true;
        var latest = samples.getLast();
        return incoming.tick() >= latest.tick() && incoming.revision() >= latest.revision();
    }

    /** Returns true when callers must also reset vanilla entity interpolation history. */
    public boolean accept(VehiclePoseSnapshot incoming, double time) {
        boolean reset = samples.isEmpty() || incoming.revision() != samples.getLast().revision();
        var old = reset ? null : state(time);
        if (reset) samples.clear();
        else if (samples.getLast().tick() == incoming.tick()) samples.removeLast();
        samples.add(incoming);
        if (samples.size() > HISTORY_SIZE) samples.removeFirst();
        correctionTime = time;
        if (reset) {
            error = velocityError = Motion.ZERO;
            frontError = rearError = null;
        } else {
            var next = raw(time);
            error = Motion.between(next.pose().body(), old.pose().body());
            velocityError = old.velocity().add(next.velocity().scale(-1));
            frontError = bogieError(next.pose().frontBogie(), old.pose().frontBogie());
            rearError = bogieError(next.pose().rearBogie(), old.pose().rearBogie());
        }
        return reset;
    }

    public VehiclePose sample(double time) { return state(time).pose(); }

    private State state(double time) {
        var base = raw(time);
        double u = Math.clamp((time - correctionTime) / CORRECTION_TICKS, 0, 1);
        if (u == 1 || frontError == null) return base;
        double weight = 2 * u * u * u - 3 * u * u + 1;
        double tangent = u * u * u - 2 * u * u + u;
        var delta = error.scale(weight).add(velocityError.scale(tangent * CORRECTION_TICKS));
        var derivative = error.scale((6 * u * u - 6 * u) / CORRECTION_TICKS)
                .add(velocityError.scale(3 * u * u - 4 * u + 1));
        var pose = base.pose();
        return new State(new VehiclePose(delta.apply(pose.body()), correctBogie(pose.frontBogie(), frontError, weight),
                correctBogie(pose.rearBogie(), rearError, weight)), base.velocity().add(derivative));
    }

    private State raw(double time) {
        var first = samples.getFirst();
        if (time < first.tick()) return new State(first.pose(), Motion.ZERO);
        for (int i = 1; i < samples.size(); i++) {
            var a = samples.get(i - 1);
            var b = samples.get(i);
            if (time < b.tick()) return interpolate(a, b, time);
        }
        var latest = samples.getLast();
        double ahead = time - latest.tick();
        double elapsed = Math.clamp(ahead, 0, PREDICTION_TICKS);
        var motion = latest.predicting() ? latest.motion() : Motion.ZERO;
        return new State(withBody(latest.pose(), motion.scale(elapsed).apply(latest.pose().body())),
                ahead <= PREDICTION_TICKS ? motion : Motion.ZERO);
    }

    private static State interpolate(VehiclePoseSnapshot a, VehiclePoseSnapshot b, double time) {
        double duration = b.tick() - a.tick();
        double u = (time - a.tick()) / duration;
        var difference = Motion.between(a.pose().body(), b.pose().body());
        var offset = difference.scale(-2 * u * u * u + 3 * u * u)
                .add(a.motion().scale((u * u * u - 2 * u * u + u) * duration))
                .add(b.motion().scale((u * u * u - u * u) * duration));
        var velocity = difference.scale((-6 * u * u + 6 * u) / duration)
                .add(a.motion().scale(3 * u * u - 4 * u + 1))
                .add(b.motion().scale(3 * u * u - 2 * u));
        return new State(new VehiclePose(offset.apply(a.pose().body()),
                a.pose().frontBogie().interpolate(b.pose().frontBogie(), u),
                a.pose().rearBogie().interpolate(b.pose().rearBogie(), u)), velocity);
    }

    private static VehiclePose withBody(VehiclePose pose, VehiclePose.Body body) {
        return new VehiclePose(body, pose.frontBogie(), pose.rearBogie());
    }

    private static VehiclePose.Bogie bogieError(VehiclePose.Bogie base, VehiclePose.Bogie previous) {
        return VehiclePose.Bogie.of(VehiclePose.subtract(previous.position(), base.position()),
                previous.rotation().mul(base.rotation().conjugate()).normalize());
    }

    private static VehiclePose.Bogie correctBogie(VehiclePose.Bogie base, VehiclePose.Bogie error, double weight) {
        var p = error.position();
        var offset = new TrackVector(p.x() * weight, p.y() * weight, p.z() * weight);
        return VehiclePose.Bogie.of(VehiclePose.add(base.position(), offset),
                new Quaternionf().slerp(error.rotation(), (float)weight).mul(base.rotation()).normalize());
    }
}
