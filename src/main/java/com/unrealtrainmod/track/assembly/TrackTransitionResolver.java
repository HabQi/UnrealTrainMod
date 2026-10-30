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

/** Topological availability only; reservations are checked separately. */
public final class TrackTransitionResolver {
    private TrackTransitionResolver() {}

    public static boolean isPathEnabled(TrackAssemblyInstance assembly, String pathId) {
        return assembly.definition().routes().get(pathId).enabled(assembly.state());
    }

    public static boolean isSegmentEnabled(TrackGraph graph, UUID segment) {
        return isSegmentEnabled(graph, segment, TrackTraversalContext.STRICT);
    }

    public static boolean isSegmentEnabled(TrackGraph graph, UUID segment, TrackTraversalContext context) {
        var assembly = graph.assemblyForSegment(segment);
        return assembly == null || assembly.paths().entrySet().stream().anyMatch(entry ->
                (context.allows(assembly, entry.getKey(), false) || context.allows(assembly, entry.getKey(), true))
                && entry.getValue().steps().stream().anyMatch(step -> step.segmentId().equals(segment)));
    }

    public static boolean permits(TrackGraph graph, TrackConnection incoming, TrackConnection outgoing) {
        return permits(graph, incoming, outgoing, TrackTraversalContext.STRICT);
    }

    public static boolean permits(TrackGraph graph, TrackConnection incoming, TrackConnection outgoing, TrackTraversalContext context) {
        var in = graph.segment(incoming.segmentId());
        var out = graph.segment(outgoing.segmentId());
        UUID node = incoming.fromStart() ? in.endNodeId() : in.startNodeId();
        if (!node.equals(outgoing.fromStart() ? out.startNodeId() : out.endNodeId())) return false;
        var a = graph.assemblyForSegment(incoming.segmentId());
        var b = graph.assemblyForSegment(outgoing.segmentId());
        if (a == null && b == null) return true;
        if (a != null && b != null) return a.id().equals(b.id()) && isSequenceEnabled(a, List.of(incoming, outgoing), context);
        var assembly = a == null ? b : a;
        for (var entry : assembly.paths().entrySet()) {
            var path = entry.getValue();
            for (boolean reverse : List.of(false, true)) {
                if (!context.allows(assembly, entry.getKey(), reverse)) continue;
                var direction = reverse ? path.reversedSteps() : path.steps();
                if (a == null && direction.getFirst().equals(outgoing)) return true;
                if (b == null && direction.getLast().equals(incoming)) return true;
            }
        }
        return false;
    }

    /** A run inside one assembly must belong to a single enabled path, including its direction. */
    public static boolean isSequenceEnabled(TrackAssemblyInstance assembly, List<TrackConnection> run) {
        return isSequenceEnabled(assembly, run, TrackTraversalContext.STRICT);
    }

    public static boolean isSequenceEnabled(TrackAssemblyInstance assembly, List<TrackConnection> run, TrackTraversalContext context) {
        for (var entry : assembly.paths().entrySet()) {
            var path = entry.getValue();
            if ((context.allows(assembly, entry.getKey(), false) && Collections.indexOfSubList(path.steps(), run) >= 0)
                    || (context.allows(assembly, entry.getKey(), true) && Collections.indexOfSubList(path.reversedSteps(), run) >= 0)) return true;
        }
        return false;
    }

    /** Handles partial paths at either end, while rejecting splices through shared internal nodes. */
    public static boolean isRouteEnabled(TrackGraph graph, List<TrackConnection> steps) {
        return isRouteEnabled(graph, steps, TrackTraversalContext.STRICT);
    }

    public static boolean isRouteEnabled(TrackGraph graph, List<TrackConnection> steps, TrackTraversalContext context) {
        var run = new ArrayList<TrackConnection>();
        TrackAssemblyInstance current = null;
        TrackConnection previous = null;
        for (var step : steps) {
            if (previous != null && !permits(graph, previous, step, context)) return false;
            var owner = graph.assemblyForSegment(step.segmentId());
            if (owner != current) {
                if (current != null && !isSequenceEnabled(current, run, context)) return false;
                run.clear(); current = owner;
            }
            if (owner != null) run.add(step);
            previous = step;
        }
        return current == null || isSequenceEnabled(current, run, context);
    }
}
