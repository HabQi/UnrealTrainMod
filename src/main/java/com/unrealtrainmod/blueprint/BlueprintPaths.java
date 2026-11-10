/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.blueprint;

import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Unique-continuation traversal. A clicked branch segment is an explicit waypoint. */
public final class BlueprintPaths {
    public interface Path {
        double length(); TrackPose sample(double distance);
        default java.util.Set<java.util.UUID> segments() { return java.util.Set.of(); }
    }
    private BlueprintPaths() {}

    public static Path resolve(TrackGraph graph, List<BlueprintPlacementRequest.Anchor> supplied, boolean reverse) {
        var anchors = new ArrayList<>(supplied);
        if (reverse) java.util.Collections.reverse(anchors);
        if (anchors.size() < 2) throw new IllegalArgumentException("binding");
        var pieces = new ArrayList<Path>();
        for (int i = 1; i < anchors.size(); i++) pieces.add(between(graph, anchors.get(i - 1), anchors.get(i)));
        for (int i = 1; i < pieces.size(); i++) {
            var a = pieces.get(i - 1).sample(pieces.get(i - 1).length()).tangent(); var b = pieces.get(i).sample(0).tangent();
            if (a.x() * b.x() + a.y() * b.y() + a.z() * b.z() <= 0) throw new IllegalArgumentException("direction");
        }
        double length = pieces.stream().mapToDouble(Path::length).sum();
        if (length <= 1e-6 || length > 65536) throw new IllegalArgumentException("path_length");
        return new Path() {
            public java.util.Set<java.util.UUID> segments() {
                var ids = new HashSet<java.util.UUID>(); pieces.forEach(p -> ids.addAll(p.segments())); return java.util.Set.copyOf(ids);
            }
            public double length() { return length; }
            public TrackPose sample(double s) {
                for (int i = 0; i < pieces.size(); i++) {
                    var p = pieces.get(i);
                    if (s < p.length() || i == pieces.size() - 1) return p.sample(Math.max(0, Math.min(s, p.length())));
                    s -= p.length();
                }
                throw new IllegalStateException();
            }
        };
    }
    private static Path between(TrackGraph graph, BlueprintPlacementRequest.Anchor from, BlueprintPlacementRequest.Anchor to) {
        var start = graph.segment(from.segment()); var end = graph.segment(to.segment());
        if (from.distance() > start.geometry().getLength() || to.distance() > end.geometry().getLength()) throw new IllegalArgumentException("path");
        var candidates = new ArrayList<List<TrackConnection>>();
        for (boolean forward : new boolean[]{true, false}) {
            if (from.segment().equals(to.segment()) && forward != (to.distance() > from.distance())) continue;
            var steps = walk(graph, new TrackConnection(from.segment(), forward), to.segment());
            if (steps != null) candidates.add(steps);
        }
        if (candidates.size() != 1) throw new IllegalArgumentException("branch");
        var steps = candidates.getFirst(); var route = TrackRoute.of(graph, steps); var sampler = new TrackPathSampler(route);
        double offset = steps.getFirst().fromStart() ? from.distance() : start.geometry().getLength() - from.distance();
        double last = steps.getLast().fromStart() ? to.distance() : end.geometry().getLength() - to.distance();
        double length = route.getLength() - end.geometry().getLength() + last - offset;
        if (length <= 1e-6) throw new IllegalArgumentException("path_length");
        return new Path() {
            public java.util.Set<java.util.UUID> segments() { return steps.stream().map(TrackConnection::segmentId).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
            public double length() { return length; }
            public TrackPose sample(double distance) { return sampler.sample(Math.min(route.getLength(), offset + distance)); }
        };
    }
    private static List<TrackConnection> walk(TrackGraph graph, TrackConnection first, java.util.UUID target) {
        var steps = new ArrayList<TrackConnection>(); var visited = new HashSet<java.util.UUID>(); var current = first;
        while (steps.size() < 2048 && visited.add(current.segmentId())) {
            steps.add(current);
            if (current.segmentId().equals(target)) return steps;
            var segment = graph.segment(current.segmentId());
            var exit = current.fromStart() ? segment.endNodeId() : segment.startNodeId();
            var choices = new ArrayList<TrackConnection>();
            for (var next : graph.connections(exit)) if (!next.segmentId().equals(current.segmentId())
                    && TrackTransitionResolver.permits(graph, current, next)) choices.add(next);
            var explicit = choices.stream().filter(c -> c.segmentId().equals(target)).toList();
            if (explicit.size() == 1) current = explicit.getFirst();
            else if (choices.size() == 1) current = choices.getFirst();
            else return null;
        }
        return null;
    }

    public static BlueprintPlacementRequest.Anchor pick(TrackGraph graph, Vec3 hit) {
        BlueprintPlacementRequest.Anchor best = null; double score = 4;
        for (var segment : graph.segmentsInChunk(net.minecraft.util.Mth.floor(hit.x) >> 4, net.minecraft.util.Mth.floor(hit.z) >> 4)) {
            var geometry = segment.geometry(); double length = geometry.getLength(), distance = 0, nearest = Double.POSITIVE_INFINITY;
            int count = Math.max(1, (int)Math.ceil(length));
            for (int i = 0; i <= count; i++) {
                double s = length * i / count; double d = squared(geometry.sample(s), hit);
                if (d < nearest) { distance = s; nearest = d; }
            }
            double lo = Math.max(0, distance - 1), hi = Math.min(length, distance + 1);
            for (int i = 0; i < 24; i++) {
                double l = (2 * lo + hi) / 3, r = (lo + 2 * hi) / 3;
                if (squared(geometry.sample(l), hit) < squared(geometry.sample(r), hit)) hi = r; else lo = l;
            }
            distance = (lo + hi) / 2; double d = squared(geometry.sample(distance), hit);
            if (d < score) { score = d; best = new BlueprintPlacementRequest.Anchor(segment.id(), distance); }
        }
        if (best == null) throw new IllegalArgumentException("no_track");
        return best;
    }
    private static double squared(TrackPose pose, Vec3 hit) {
        var p = pose.position(); return hit.distanceToSqr(p.x(), p.y(), p.z());
    }
}
