/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeTurnoutBladeChain.Node;
import com.unrealtrainmod.track.mesh.NativeTurnoutBladeChain.Stroke;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.Frame;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.InvalidLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.unrealtrainmod.track.mesh.NativeTurnoutBladeChain.REFERENCE_JOINT_LIMIT;
import static com.unrealtrainmod.track.mesh.NativeTurnoutLayout.direction;
import static com.unrealtrainmod.track.mesh.NativeTurnoutLayout.distance;
import static com.unrealtrainmod.track.mesh.NativeTurnoutLayout.wrap;

/** Fits and ranks the r11 connection links shared by ordinary turnouts and crossover cores. */
final class NativeTurnoutConnections {
    private static final double SHORT_LENGTH = 0.10;
    private static final double COMPACT_LENGTH = 2.0;
    private static final double COMPACT_MIN_LINK = 0.30;
    private static final double STANDALONE_JOINT_LIMIT = Math.toRadians(0.14);
    private static final double STANDALONE_COMPACT_JOINT_LIMIT = Math.toRadians(0.21);
    private static final double CROSSOVER_COMPACT_JOINT_LIMIT = Math.toRadians(0.28);

    enum Policy { STANDALONE, CROSSOVER_CORE, REFERENCE }

    private record Span(int from, int to, Frame frame, double weight) {
        Span withWeight(double value) { return new Span(from, to, frame, value); }
    }

    private record Path(Span span, Path previous, int segments, int shortSegments, double weightError) {
        List<Span> spans() {
            var ordered = new ArrayList<Span>(segments);
            for (var path = this; path.span() != null; path = path.previous()) ordered.add(path.span());
            return ordered.reversed();
        }
    }

    private record Accepted(NativeTurnoutBladeChain chain, int shortSegments, double motionError) {
        int segments() { return chain.frames().size(); }
    }

    // Prefer promising partial paths before fitting the full stroke and comparing actual displacement.
    private static final Comparator<Path> PATH_ORDER = Comparator.comparingInt(Path::segments)
            .thenComparingInt(Path::shortSegments).thenComparingDouble(Path::weightError);
    private static final Comparator<Accepted> ACCEPTED_ORDER = Comparator.comparingInt(Accepted::segments)
            .thenComparingInt(Accepted::shortSegments).thenComparingDouble(Accepted::motionError);
    private static final Path START = new Path(null, null, 0, 0, 0);

    private NativeTurnoutConnections() {}

    static NativeTurnoutBladeChain merge(NativeTurnoutBladeChain reference, NativeRailCurve spine, Stroke stroke, Policy policy) {
        if (policy == Policy.REFERENCE) return reference;
        var search = new Search(reference, stroke);
        var nodes = reference.nodes();
        // Keep the tapered blade, its first 1.45 m rail, and the fixed last link.
        int end = nodes.size() - 2;
        var choices = connectionChoices(reference, spine, search.motion.fullOpening(), end, stroke.scale());
        boolean compact = nodes.get(end).distance() - nodes.getFirst().distance() <= COMPACT_LENGTH * stroke.scale()
                && search.best.shortSegments() > 0;

        offerChordPaths(choices, search, REFERENCE_JOINT_LIMIT);
        if (policy == Policy.STANDALONE && !compact) offerChordPaths(choices, search, STANDALONE_JOINT_LIMIT);
        if (compact) {
            double jointLimit = policy == Policy.STANDALONE ? STANDALONE_COMPACT_JOINT_LIMIT : CROSSOVER_COMPACT_JOINT_LIMIT;
            offerCompactPaths(choices, search, jointLimit);
        }
        for (int count = search.minimumCount(REFERENCE_JOINT_LIMIT); count <= search.bestConnectionCount(); count++) {
            var path = fitEvenTurns(choices, count, stroke.scale());
            if (path != null) search.offer(path.spans(), REFERENCE_JOINT_LIMIT);
        }
        return search.best.chain();
    }

    private static List<List<Span>> connectionChoices(NativeTurnoutBladeChain reference, NativeRailCurve spine,
                                                      TrackVector[] open, int end, double scale) {
        var nodes = reference.nodes();
        var choices = new ArrayList<List<Span>>(end);
        for (int from = 0; from < end; from++) {
            var spans = new ArrayList<Span>();
            choices.add(spans);
            var start = nodes.get(from);
            for (int to = from + 1; to <= end; to++) {
                var finish = nodes.get(to);
                if (finish.distance() - start.distance() > NativeFixedRails.MAX_LENGTH * scale) break;
                if (to == from + 1) {
                    spans.add(new Span(from, to, reference.frames().get(from + 2), reference.weights().get(from + 2)));
                    continue;
                }
                if (!NativeFixedRails.fitsStrip(spine, start.distance(), finish.distance(), scale)) continue;
                var frame = Frame.between(start.position(), finish.position(), true);
                double turn = wrap(direction(open[from + 2], open[to + 2]) - frame.yaw());
                spans.add(new Span(from, to, frame, Math.clamp(turn / reference.travel(), 0, 1)));
            }
        }
        return choices;
    }

    private static void offerChordPaths(List<List<Span>> choices, Search search, double jointLimit) {
        double budgetTravel = Math.abs(search.reference.travel());
        for (int pass = 0; pass < 3; pass++) {
            double nextBudget = budgetTravel;
            for (var path : chordPaths(choices, budgetTravel, jointLimit, search.stroke.scale()))
                nextBudget = Math.max(nextBudget, search.offer(path.spans(), jointLimit));
            if (nextBudget == budgetTravel) return;
            // A recalculated opening can tighten the joint constraint used by the search.
            budgetTravel = nextBudget;
        }
    }

    private static List<Path> chordPaths(List<List<Span>> choices, double travel, double jointLimit, double scale) {
        int end = choices.size();
        var endingAt = new ArrayList<List<Path>>(end + 1);
        for (int i = 0; i <= end; i++) endingAt.add(new ArrayList<>());
        endingAt.getFirst().add(START);
        for (int from = 0; from < end; from++) {
            for (var span : choices.get(from)) {
                if (span.to() == end && span.weight() * travel > jointLimit) continue;
                Path best = null;
                for (var previous : endingAt.get(from)) {
                    double previousWeight = previous.span() == null ? 1 : previous.span().weight();
                    if (Math.abs(previousWeight - span.weight()) * travel <= jointLimit
                            && (best == null || PATH_ORDER.compare(previous, best) < 0)) best = previous;
                }
                if (best != null) endingAt.get(span.to()).add(append(best, span, scale, 0));
            }
        }
        return endingAt.getLast();
    }

    private static Path fitEvenTurns(List<List<Span>> choices, int count, double scale) {
        int end = choices.size();
        var paths = new Path[end + 1];
        paths[0] = START;
        for (int link = 0; link < count; link++) {
            double weight = (double)(count - link) / (count + 1);
            var next = new Path[end + 1];
            for (int from = 0; from < end; from++) {
                var previous = paths[from];
                if (previous == null) continue;
                for (var span : choices.get(from)) {
                    int to = span.to();
                    if (end - to < count - link - 1) continue;
                    double weightError = span.frame().length() * Math.abs(span.weight() - weight);
                    var candidate = append(previous, span.withWeight(weight), scale, weightError);
                    if (next[to] == null || PATH_ORDER.compare(candidate, next[to]) < 0) next[to] = candidate;
                }
            }
            paths = next;
        }
        return paths[end];
    }

    private static Path append(Path previous, Span span, double scale, double weightError) {
        return new Path(span, previous, previous.segments() + 1,
                previous.shortSegments() + (isShort(span.frame(), scale) ? 1 : 0), previous.weightError() + weightError);
    }

    private static void offerCompactPaths(List<List<Span>> choices, Search search, double jointLimit) {
        var nodes = search.reference.nodes();
        double minLength = COMPACT_MIN_LINK * search.stroke.scale();
        double endStation = nodes.get(choices.size()).distance();
        for (int count = search.minimumCount(jointLimit); count <= search.bestConnectionCount(); count++) {
            if (endStation - nodes.getFirst().distance() < count * minLength) break;
            offerCompactPaths(choices, search, jointLimit, minLength, endStation, count, 0, new ArrayList<>(count));
        }
    }

    private static void offerCompactPaths(List<List<Span>> choices, Search search, double jointLimit, double minLength,
                                          double endStation, int remaining, int from, ArrayList<Span> path) {
        if (remaining == 0) {
            if (from == choices.size()) offerCompactWeights(path, search, jointLimit);
            return;
        }
        if (from == choices.size() || endStation - search.reference.nodes().get(from).distance() < remaining * minLength)
            return;
        for (var span : choices.get(from)) {
            if (span.frame().length() < minLength) continue;
            path.add(span);
            offerCompactPaths(choices, search, jointLimit, minLength, endStation, remaining - 1, span.to(), path);
            path.removeLast();
        }
    }

    private static void offerCompactWeights(List<Span> spans, Search search, double jointLimit) {
        int count = spans.size();
        for (int step = 0; step <= 10; step++) {
            double mix = step / 10.0;
            var weighted = new ArrayList<Span>(count);
            for (int i = 0; i < count; i++) {
                var span = spans.get(i);
                double uniform = (double)(count - i) / (count + 1);
                weighted.add(span.withWeight(uniform + (span.weight() - uniform) * mix));
            }
            search.offer(weighted, jointLimit);
        }
    }

    private static NativeTurnoutBladeChain buildCandidate(NativeTurnoutBladeChain reference, List<Span> spans, Stroke stroke) {
        var nodes = reference.nodes();
        var frames = new ArrayList<>(reference.frames().subList(0, 2));
        var weights = new ArrayList<>(reference.weights().subList(0, 2));
        var retained = new ArrayList<Node>();
        retained.add(nodes.getFirst());
        for (var span : spans) {
            frames.add(span.frame());
            weights.add(span.weight());
            retained.add(nodes.get(span.to()));
        }
        frames.add(reference.frames().getLast());
        weights.add(0.0);
        retained.add(nodes.getLast());
        double travel;
        try { travel = stroke.solveTravel(frames, weights); }
        catch (InvalidLayout failure) { return null; }
        var tangents = new ArrayList<>(reference.tangents().subList(0, 2));
        for (var node : retained) tangents.add(node.tangent());
        return new NativeTurnoutBladeChain(frames, tangents, weights, travel, retained);
    }

    private static boolean isShort(Frame frame, double scale) { return frame.length() < SHORT_LENGTH * scale; }

    private static int shortConnections(NativeTurnoutBladeChain chain, double scale) {
        int count = 0;
        for (int i = 2; i < chain.frames().size() - 1; i++)
            if (isShort(chain.frames().get(i), scale)) count++;
        return count;
    }

    private static final class Search {
        private final NativeTurnoutBladeChain reference;
        private final Stroke stroke;
        private final MotionCheck motion;
        private final double lever;
        private Accepted best;

        Search(NativeTurnoutBladeChain reference, Stroke stroke) {
            this.reference = reference;
            this.stroke = stroke;
            motion = new MotionCheck(reference, NativeFixedRails.ERROR * stroke.scale());
            best = new Accepted(reference, shortConnections(reference, stroke.scale()), 0);
            double length = Math.hypot(stroke.tipOffset().x(), stroke.tipOffset().z());
            for (var frame : reference.frames()) length += frame.length();
            lever = length;
        }

        int bestConnectionCount() { return best.segments() - 3; }

        int minimumCount(double jointLimit) {
            // The opening cannot exceed the lever length times the total allowable turn.
            return Math.max(1, (int)Math.ceil(stroke.targetOpening() / (lever * jointLimit)) - 1);
        }

        /** Returns the opening angle to retry the chord search when the joint limit rejects a candidate. */
        double offer(List<Span> spans, double jointLimit) {
            if (spans.size() > bestConnectionCount()) return 0;
            var candidate = buildCandidate(reference, spans, stroke);
            if (candidate == null) return 0;
            if (MotionCheck.maxJoint(candidate) > jointLimit) return Math.abs(candidate.travel());
            double error = motion.maxDeviation(spans, candidate);
            if (error <= motion.limit) {
                var accepted = new Accepted(candidate, shortConnections(candidate, stroke.scale()), error);
                if (ACCEPTED_ORDER.compare(accepted, best) < 0) best = accepted;
            }
            return 0;
        }
    }

    private static final class MotionCheck {
        private final List<Node> nodes;
        private final TrackVector[][] referencePositions = new TrackVector[4][];
        private final double limit;

        MotionCheck(NativeTurnoutBladeChain reference, double limit) {
            this.nodes = reference.nodes();
            this.limit = limit;
            for (int sample = 0; sample < referencePositions.length; sample++)
                referencePositions[sample] = reference.positions((sample + 1) / 4.0);
        }

        TrackVector[] fullOpening() { return referencePositions[referencePositions.length - 1]; }

        static double maxJoint(NativeTurnoutBladeChain candidate) {
            var weights = candidate.weights();
            double travel = candidate.travel();
            double max = 0;
            for (int i = 2; i < weights.size(); i++)
                max = Math.max(max, Math.abs(travel * (weights.get(i) - weights.get(i - 1))));
            return max;
        }

        double maxDeviation(List<Span> spans, NativeTurnoutBladeChain candidate) {
            double deviation = 0;
            for (int sample = 0; sample < referencePositions.length; sample++) {
                var fine = referencePositions[sample];
                var coarse = candidate.positions((sample + 1) / 4.0);
                for (int i = 0; i < 2; i++) deviation = Math.max(deviation, distance(fine[i], coarse[i]));
                if (deviation > limit) return deviation;
                for (int i = 0; i < spans.size(); i++) {
                    var span = spans.get(i);
                    double from = nodes.get(span.from()).distance(), to = nodes.get(span.to()).distance();
                    var start = coarse[i + 2];
                    var delta = NativeRailCurve.add(coarse[i + 3], start, -1);
                    for (int j = span.from(); j <= span.to(); j++) {
                        var point = NativeRailCurve.add(start, delta, (nodes.get(j).distance() - from) / (to - from));
                        deviation = Math.max(deviation, distance(point, fine[j + 2]));
                        if (deviation > limit) return deviation;
                    }
                }
            }
            return deviation;
        }
    }
}

