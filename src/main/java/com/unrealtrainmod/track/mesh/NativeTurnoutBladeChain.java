/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.Frame;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.InvalidLayout;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.Problem;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.DoubleFunction;

import static com.unrealtrainmod.track.mesh.NativeTurnoutLayout.SOLVE_TOLERANCE;

/** Reference chain and opening calculation for the r11 turnout and crossover cores. */
record NativeTurnoutBladeChain(List<Frame> frames, List<Double> tangents, List<Double> weights, double travel,
                              List<NativeTurnoutBladeChain.Node> nodes) {
    static final double CONNECTION_STEP = 0.5;
    static final double REFERENCE_JOINT_LIMIT = Math.toRadians(0.1);
    private static final double BLADE_OPENING = 0.12;

    record Node(double distance, TrackVector position, double tangent) {}

    record Stroke(TrackVector lateral, TrackVector tipOffset, double inner, double scale) {
        double targetOpening() { return BLADE_OPENING * scale; }

        double solveTravel(List<Frame> frames, List<Double> weights) {
            // Per link: closed projection, its yaw derivative, and signed rotation weight.
            var coefficients = new double[(frames.size() + 1) * 3];
            double derivativeBound = 0, lateralLength = Math.hypot(lateral.x(), lateral.z());
            for (int i = 0; i < frames.size(); i++) {
                var frame = frames.get(i);
                double horizontal = frame.length() * Math.cos(frame.pitch());
                double sine = Math.sin(frame.yaw()), cosine = Math.cos(frame.yaw()), weight = weights.get(i);
                coefficients[i * 3] = horizontal * (lateral.x() * sine + lateral.z() * cosine);
                coefficients[i * 3 + 1] = horizontal * (lateral.x() * cosine - lateral.z() * sine);
                coefficients[i * 3 + 2] = inner * weight;
                derivativeBound += horizontal * lateralLength * weight;
            }
            // Chain displacement is minus the rotated links, plus the rotated contact-tip offset.
            int tipIndex = frames.size() * 3;
            coefficients[tipIndex] = -NativeRailCurve.dot(tipOffset, lateral);
            coefficients[tipIndex + 1] = -(lateral.x()*tipOffset.z() - lateral.z()*tipOffset.x());
            coefficients[tipIndex + 2] = inner;
            derivativeBound += Math.hypot(tipOffset.x(), tipOffset.z()) * lateralLength;
            double low = 0, high = 0.15, target = targetOpening();
            if (opening(high, coefficients, inner) < target) throw new InvalidLayout(Problem.SPACE);
            while ((high - low) * derivativeBound > SOLVE_TOLERANCE * scale) {
                double mid = (low + high) / 2;
                if (opening(mid, coefficients, inner) < target) low = mid; else high = mid;
            }
            return inner * (low + high) / 2;
        }
    }

    static NativeTurnoutBladeChain reference(double tipStation, double heelStation, double startDistance, double root,
                                             DoubleFunction<Node> sample, Stroke stroke) {
        var profile = NativeLayoutProfile.current();
        double length = root - startDistance;
        int count = Math.max(3, (int)Math.ceil(length / (CONNECTION_STEP * stroke.scale())));
        var nodes = new ArrayList<Node>(count + 1);
        for (int i = 0; i <= count; i++) nodes.add(sample.apply(i == count ? root : startDistance + length * i / count));
        var tip = sample.apply(tipStation);
        var heel = sample.apply(heelStation);
        var frames = new ArrayList<Frame>(count + 2);
        frames.add(Frame.between(tip.position(), heel.position()));
        frames.add(Frame.between(heel.position(), nodes.getFirst().position()));
        for (int i = 0; i < count; i++) frames.add(Frame.between(nodes.get(i).position(), nodes.get(i + 1).position()));
        while (true) {
            var weights = motionWeights(frames);
            double travel = stroke.solveTravel(frames, weights);
            var split = new BitSet(nodes.size() - 1);
            // Closed-pose end tangents match. Their full-stroke rotation difference bounds
            // the tangent discontinuity at every intermediate pose, in both switch directions.
            for (int i = 3; i < frames.size(); i++) {
                if (Math.abs(travel * (weights.get(i) - weights.get(i - 1))) <= REFERENCE_JOINT_LIMIT) continue;
                int link = frames.get(i - 1).length() >= frames.get(i).length() ? i - 1 : i;
                split.set(link - 2);
            }
            if (split.isEmpty()) {
                var tangents = new ArrayList<Double>(nodes.size() + 2);
                tangents.add(tip.tangent());
                tangents.add(heel.tangent());
                for (var node : nodes) tangents.add(node.tangent());
                return new NativeTurnoutBladeChain(frames, tangents, weights, travel, nodes);
            }
            if (profile != null) profile.count("motionRefinements");
            var nextNodes = new ArrayList<Node>(nodes.size() + split.cardinality());
            var nextFrames = new ArrayList<Frame>(frames.size() + split.cardinality());
            nextNodes.add(nodes.getFirst());
            nextFrames.add(frames.get(0)); nextFrames.add(frames.get(1));
            for (int i = 0; i + 1 < nodes.size(); i++) {
                var start = nodes.get(i); var end = nodes.get(i + 1);
                if (split.get(i)) {
                    var mid = sample.apply((start.distance() + end.distance()) / 2);
                    nextNodes.add(mid);
                    nextFrames.add(Frame.between(start.position(), mid.position()));
                    nextFrames.add(Frame.between(mid.position(), end.position()));
                } else nextFrames.add(frames.get(i + 2));
                nextNodes.add(end);
            }
            nodes = nextNodes; frames = nextFrames;
        }
    }

    TrackVector[] positions(double phase) {
        var positions = new TrackVector[frames.size()+1];
        positions[frames.size()] = frames.getLast().end();
        for (int i = frames.size()-1; i >= 0; i--) {
            var frame = frames.get(i);
            double yaw = frame.yaw()+travel*weights.get(i)*phase;
            double horizontal = frame.length()*Math.cos(frame.pitch());
            var end = positions[i+1];
            positions[i] = new TrackVector(end.x()-horizontal*Math.sin(yaw),end.y()-frame.length()*Math.sin(frame.pitch()),
                    end.z()-horizontal*Math.cos(yaw));
        }
        return positions;
    }

    private static List<Double> motionWeights(List<Frame> frames) {
        // The blade and added ordinary rail precede the deforming connection curve.
        double length = 0;
        for (int i = 2; i < frames.size(); i++) length += frames.get(i).length();
        double first = frames.get(2).length() / 2, last = length - frames.getLast().length() / 2;
        double inverseRange = 1 / (last - first);
        var weights = new ArrayList<Double>(frames.size()); weights.add(1.0); weights.add(1.0);
        double along = 0;
        for (int i = 2; i < frames.size(); i++) {
            var frame = frames.get(i);
            double t = Math.clamp((along + frame.length() / 2 - first) * inverseRange, 0, 1);
            // Quintic falloff has zero first and second derivatives at both ends. Using
            // link centers distributes rotation along the chain without a forced last-link jump.
            weights.add(i == 2 ? 1.0 : i == frames.size() - 1 ? 0.0 : 1 - t*t*t*(10 + t*(-15 + 6*t)));
            along += frame.length();
        }
        return weights;
    }

    /** Project the change from the closed pose directly; no world positions or temporary nodes. */
    private static double opening(double angle, double[] coefficients, double inner) {
        double displacement = 0;
        for (int i = 0; i < coefficients.length; i += 3) {
            double rotation = angle * coefficients[i + 2];
            if (rotation == 0) continue;
            displacement += coefficients[i] * (Math.cos(rotation) - 1) + coefficients[i + 1] * Math.sin(rotation);
        }
        return inner * displacement;
    }
}
