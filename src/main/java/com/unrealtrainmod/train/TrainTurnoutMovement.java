/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Pure movement proposals, using route occurrences rather than segment IDs as positions. */
final class TrainTurnoutMovement {
    record Action(UUID assemblyId, String pathId, boolean begin, boolean throwSwitch) {}
    private record Part(TrackAssemblyInstance assembly, String pathId, double start, double length,
                        double pathStart, double pathLength, boolean aligned) {
        double distanceFromA(double position) {
            double local = Math.clamp(position - start, 0, length);
            return pathStart + (aligned ? local : length - local);
        }
        boolean towardA(boolean forward) { return aligned != forward; }
    }
    private final TrainConsist train;
    private final List<Part> parts;

    TrainTurnoutMovement(TrackGraph graph, TrainConsist train) {
        this.train = train;
        this.parts = parts(graph, train);
    }

    private static List<Part> parts(TrackGraph graph, TrainConsist train) {
        var result = new ArrayList<Part>();
        double start = 0;
        for (int i = 0; i < train.route().connections().size(); i++) {
            var connection = train.route().connections().get(i);
            double length = train.route().segmentLength(i);
            var assembly = graph.assemblyForSegment(connection.segmentId());
            if (assembly != null) {
                for (var entry : assembly.paths().entrySet()) {
                    double total = assembly.geometry().paths().get(entry.getKey()).stream().mapToDouble(g -> g.getLength()).sum();
                    double prefix = 0;
                    for (var step : entry.getValue().steps()) {
                        double partLength = graph.segment(step.segmentId()).geometry().getLength();
                        if (step.segmentId().equals(connection.segmentId())) {
                            result.add(new Part(assembly, entry.getKey(), start, length, prefix, total, step.equals(connection)));
                        }
                        prefix += partLength;
                    }
                }
            }
            start += length;
        }
        return result;
    }

    private static double leadingBogie(TrainConsist train, boolean forward) {
        double offset = forward ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (var car : train.cars()) {
            var spec = VehicleCatalog.find(car.modelId()).motionSpec();
            double a = car.offset() + spec.frontBogieOffset(), b = car.offset() + spec.rearBogieOffset();
            offset = forward ? Math.max(offset, Math.max(a, b)) : Math.min(offset, Math.min(a, b));
        }
        return offset;
    }

    double nextBoundary(double from, double next) {
        boolean forward = next > from;
        double leading = leadingBogie(train, forward);
        for (var part : parts) {
            if (!part.towardA(forward) || TrackTransitionResolver.isPathEnabled(part.assembly(), part.pathId())) continue;
            double trigger = part.pathLength() * 0.1 - part.pathStart();
            if (trigger < 0 || trigger > part.length()) continue;
            double boundary = part.start() + (part.aligned() ? trigger : part.length() - trigger) - leading;
            if (forward && boundary > from) next = Math.min(next, boundary);
            else if (!forward && boundary < from) next = Math.max(next, boundary);
        }
        return next;
    }

    List<Action> propose(double from, double to) {
        boolean forward = to > from;
        double min = to, max = to;
        for (var car : train.cars()) {
            var model = VehicleCatalog.find(car.modelId());
            min = Math.min(min, to + car.offset() + TrackOccupancyManager.rear(model));
            max = Math.max(max, to + car.offset() + TrackOccupancyManager.front(model));
        }
        double bogie = to + leadingBogie(train, forward);
        var actions = new LinkedHashMap<UUID, Action>();
        var context = TrainRouteWindow.context(train, true);
        for (var part : parts) {
            if (min >= part.start() + part.length() || max <= part.start()
                    || TrackTransitionResolver.isPathEnabled(part.assembly(), part.pathId())) continue;
            var passage = part.assembly().trailingPass();
            if (!context.allows(part.assembly(), part.pathId(), !part.aligned())) {
                throw new IllegalArgumentException("Closed turnout path cannot be entered in this direction");
            }
            boolean trigger = part.towardA(forward) && bogie >= part.start() - 1e-9 && bogie <= part.start() + part.length() + 1e-9
                    && part.distanceFromA(bogie) <= part.pathLength() * 0.1 + 1e-9;
            var previous = actions.get(part.assembly().id());
            if (previous != null && !previous.pathId().equals(part.pathId())) {
                throw new IllegalArgumentException("One consist cannot squeeze two different paths of the same turnout");
            }
            actions.put(part.assembly().id(), new Action(part.assembly().id(), part.pathId(), passage == null,
                    trigger || previous != null && previous.throwSwitch()));
        }
        // A new lock must also preserve any already occupied, currently open path of this turnout.
        for (var part : parts) {
            var action = actions.get(part.assembly().id());
            if (action != null && min <= part.start() + part.length() && max >= part.start()
                    && !action.pathId().equals(part.pathId())) {
                throw new IllegalArgumentException("Trailing passage would close another occupied path");
            }
        }
        return List.copyOf(actions.values());
    }
}
