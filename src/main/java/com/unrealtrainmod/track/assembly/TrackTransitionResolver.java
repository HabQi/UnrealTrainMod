/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Topological availability only; reservations are checked separately. */
public final class TrackTransitionResolver {
    private TrackTransitionResolver() {}

    public static boolean isPathEnabled(TrackAssemblyInstance assembly, String pathId) {
        return assembly.definition().routes().get(pathId).enabled(assembly.state());
    }

    public static boolean isSegmentEnabled(TrackGraph graph, UUID segment) {
        var assembly = graph.assemblyForSegment(segment);
        return assembly == null || assembly.paths().entrySet().stream().anyMatch(entry -> isPathEnabled(assembly, entry.getKey())
                && entry.getValue().steps().stream().anyMatch(step -> step.segmentId().equals(segment)));
    }

    public static boolean permits(TrackGraph graph, TrackConnection incoming, TrackConnection outgoing) {
        var in = graph.segment(incoming.segmentId());
        var out = graph.segment(outgoing.segmentId());
        UUID node = incoming.fromStart() ? in.endNodeId() : in.startNodeId();
        if (!node.equals(outgoing.fromStart() ? out.startNodeId() : out.endNodeId())) return false;
        var a = graph.assemblyForSegment(incoming.segmentId());
        var b = graph.assemblyForSegment(outgoing.segmentId());
        if (a == null && b == null) return true;
        if (a != null && b != null) return a.id().equals(b.id()) && isSequenceEnabled(a, List.of(incoming, outgoing));
        var assembly = a == null ? b : a;
        for (var entry : assembly.paths().entrySet()) {
            if (!isPathEnabled(assembly, entry.getKey())) continue;
            var path = entry.getValue();
            for (var direction : List.of(path.steps(), path.reversedSteps())) {
                if (a == null && direction.getFirst().equals(outgoing)) return true;
                if (b == null && direction.getLast().equals(incoming)) return true;
            }
        }
        return false;
    }

    /** A run inside one assembly must belong to a single enabled path, including its direction. */
    public static boolean isSequenceEnabled(TrackAssemblyInstance assembly, List<TrackConnection> run) {
        for (var entry : assembly.paths().entrySet()) {
            if (!isPathEnabled(assembly, entry.getKey())) continue;
            var path = entry.getValue();
            if (Collections.indexOfSubList(path.steps(), run) >= 0
                    || Collections.indexOfSubList(path.reversedSteps(), run) >= 0) return true;
        }
        return false;
    }

    /** Handles partial paths at either end, while rejecting splices through shared internal nodes. */
    public static boolean isRouteEnabled(TrackGraph graph, List<TrackConnection> steps) {
        var run = new ArrayList<TrackConnection>();
        TrackAssemblyInstance current = null;
        TrackConnection previous = null;
        for (var step : steps) {
            if (previous != null && !permits(graph, previous, step)) return false;
            var owner = graph.assemblyForSegment(step.segmentId());
            if (owner != current) {
                if (current != null && !isSequenceEnabled(current, run)) return false;
                run.clear(); current = owner;
            }
            if (owner != null) run.add(step);
            previous = step;
        }
        return current == null || isSequenceEnabled(current, run);
    }

    public static boolean usesEnabledPath(TrackAssemblyInstance assembly, Predicate<UUID> usesSegment) {
        return assembly.paths().entrySet().stream().anyMatch(entry -> isPathEnabled(assembly, entry.getKey())
                && entry.getValue().steps().stream().anyMatch(step -> usesSegment.test(step.segmentId())));
    }
}
