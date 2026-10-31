/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import com.unrealtrainmod.track.assembly.TrackTraversalContext;
import com.unrealtrainmod.track.path.TrackRoute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure movement proposals, using route occurrences rather than segment IDs as positions. */
final class TrainTurnoutMovement {
    record Action(UUID assemblyId, String pathId, boolean begin, boolean throwSwitch) {}
    private record Part(UUID assemblyId, String pathId, double start, double length,
                        double pathStart, double pathLength, boolean aligned) {
        double distanceFromA(double position) {
            double local = Math.clamp(position - start, 0, length);
            return pathStart + (aligned ? local : length - local);
        }
        boolean towardA(boolean forward) { return aligned != forward; }
    }
    private record PathPart(String pathId, double start, double length, boolean fromStart) {}
    private final TrackGraph graph;
    private final List<Part> parts;

    TrainTurnoutMovement(TrackGraph graph, TrackRoute route) {
        this.graph = graph;
        this.parts = parts(graph, route);
    }

    private static List<Part> parts(TrackGraph graph, TrackRoute route) {
        var result = new ArrayList<Part>();
        var paths = new HashMap<UUID, Map<UUID, List<PathPart>>>();
        double start = 0;
        for (int i = 0; i < route.connections().size(); i++) {
            var connection = route.connections().get(i);
            double length = route.segmentLength(i);
            var assembly = graph.assemblyForSegment(connection.segmentId());
            if (assembly != null) {
                var bySegment = paths.computeIfAbsent(assembly.id(), ignored -> pathParts(graph, assembly));
                for (var part : bySegment.get(connection.segmentId())) {
                    result.add(new Part(assembly.id(), part.pathId(), start, length, part.start(), part.length(),
                            part.fromStart() == connection.fromStart()));
                }
            }
            start += length;
        }
        return List.copyOf(result);
    }

    private static Map<UUID, List<PathPart>> pathParts(TrackGraph graph, TrackAssemblyInstance assembly) {
        var result = new HashMap<UUID, List<PathPart>>();
        for (var entry : assembly.paths().entrySet()) {
            double total = assembly.geometry().paths().get(entry.getKey()).stream().mapToDouble(g -> g.getLength()).sum();
            double prefix = 0;
            for (var step : entry.getValue().steps()) {
                result.computeIfAbsent(step.segmentId(), ignored -> new ArrayList<>())
                        .add(new PathPart(entry.getKey(), prefix, total, step.fromStart()));
                prefix += graph.segment(step.segmentId()).geometry().getLength();
            }
        }
        return result;
    }

    double nextBoundary(TrainMotion.Prepared motion, double from, double next) {
        boolean forward = next > from;
        double leading = motion.leadingBogie(forward);
        for (var part : parts) {
            if (!part.towardA(forward) || TrackTransitionResolver.isPathEnabled(graph.assembly(part.assemblyId()), part.pathId())) continue;
            double trigger = part.pathLength() * 0.1 - part.pathStart();
            if (trigger < 0 || trigger > part.length()) continue;
            double boundary = part.start() + (part.aligned() ? trigger : part.length() - trigger) - leading;
            if (forward && boundary > from) next = Math.min(next, boundary);
            else if (!forward && boundary < from) next = Math.max(next, boundary);
        }
        return next;
    }

    List<Action> propose(TrainMotion.Prepared motion, TrackTraversalContext context, double from, double to) {
        if (parts.isEmpty()) return List.of();
        boolean forward = to > from;
        double min = to, max = to;
        for (var car : motion.cars()) {
            min = Math.min(min, to + car.offset() + car.rear());
            max = Math.max(max, to + car.offset() + car.front());
        }
        double bogie = to + motion.leadingBogie(forward);
        var actions = new LinkedHashMap<UUID, Action>();
        for (var part : parts) {
            if (min >= part.start() + part.length() || max <= part.start()) continue;
            var assembly = graph.assembly(part.assemblyId());
            if (TrackTransitionResolver.isPathEnabled(assembly, part.pathId())) continue;
            var passage = assembly.trailingPass();
            if (!context.allows(assembly, part.pathId(), !part.aligned())) {
                throw new IllegalArgumentException("Closed turnout path cannot be entered in this direction");
            }
            boolean trigger = part.towardA(forward) && bogie >= part.start() - 1e-9 && bogie <= part.start() + part.length() + 1e-9
                    && part.distanceFromA(bogie) <= part.pathLength() * 0.1 + 1e-9;
            var previous = actions.get(part.assemblyId());
            if (previous != null && !previous.pathId().equals(part.pathId())) {
                throw new IllegalArgumentException("One consist cannot squeeze two different paths of the same turnout");
            }
            actions.put(part.assemblyId(), new Action(part.assemblyId(), part.pathId(), passage == null,
                    trigger || previous != null && previous.throwSwitch()));
        }
        // A new lock must also preserve any already occupied, currently open path of this turnout.
        for (var part : parts) {
            var action = actions.get(part.assemblyId());
            if (action != null && min <= part.start() + part.length() && max >= part.start()
                    && !action.pathId().equals(part.pathId())) {
                throw new IllegalArgumentException("Trailing passage would close another occupied path");
            }
        }
        return List.copyOf(actions.values());
    }
}
