/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.path;

import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable, finite directed path captured from a graph on its owning server thread.
 * Geometry survives graph removals unchanged. The owner must rebuild affected routes in response
 * to TrackSegmentChange; this class neither watches the graph nor accesses world/chunk state.
 */
public final class TrackRoute {
    /** prefixCount maps the supplied core's indices into this finite expanded snapshot. */
    public record Extension(TrackRoute route, int prefixCount, boolean startBoundary, boolean endBoundary) {}

    private record Growth(int count, boolean boundary) {}

    private final List<TrackConnection> connections;
    private final List<TrackGeometry> geometries;
    private final double[] distances;

    private TrackRoute(List<TrackConnection> connections, List<TrackGeometry> geometries) {
        this.connections = List.copyOf(connections);
        this.geometries = List.copyOf(geometries);
        distances = new double[geometries.size() + 1];
        for (int i = 0; i < geometries.size(); i++) {
            double next = distances[i] + geometries.get(i).getLength();
            if (!Double.isFinite(next) || next <= distances[i]) {
                throw new IllegalArgumentException("Route cumulative length must be finite and strictly increasing at segment " + i);
            }
            distances[i + 1] = next;
        }
    }

    /** Builds a snapshot in the supplied order; repeated segment IDs are allowed. */
    public static TrackRoute of(TrackGraph graph, List<TrackConnection> connections) {
        var steps = List.copyOf(connections);
        if (steps.isEmpty()) throw new IllegalArgumentException("Route must contain at least one segment");
        if (!TrackTransitionResolver.isRouteEnabled(graph, steps)) throw new IllegalArgumentException("Illegal or closed assembly path sequence");
        var geometries = new ArrayList<TrackGeometry>(steps.size());
        for (var connection : steps) {
            var geometry = graph.segment(connection.segmentId()).geometry();
            geometries.add(connection.fromStart() ? geometry : geometry.reversed());
        }
        return new TrackRoute(steps, geometries);
    }

    /**
     * Extends both ends through unique connections, stopping at ends, branches or visited segments.
     * A ring starts at the seed's entrance, contains each segment once, and has no distance wrapping.
     */
    public static TrackRoute trace(TrackGraph graph, TrackConnection seed) {
        return trace(graph, List.of(seed), null);
    }

    /**
     * Keeps an already traversed contiguous core, extending only its two ends through unique choices.
     * A non-null cutNode is a fixed finite-ring boundary which neither extension may cross.
     * The core must contain no repeated segment; explicit repeated paths still use of().
     */
    public static TrackRoute trace(TrackGraph graph, List<TrackConnection> core, UUID cutNode) {
        if (core.isEmpty()) throw new IllegalArgumentException("Route core must not be empty");
        if (!TrackTransitionResolver.isRouteEnabled(graph, core)) throw new IllegalArgumentException("Route core contains a closed assembly path");
        var steps = new ArrayDeque<>(core);
        var visited = new HashSet<UUID>();
        for (var connection : core) {
            if (!visited.add(connection.segmentId())) throw new IllegalArgumentException("Route core repeats a segment");
        }
        extendEnd(graph, core.getLast(), steps, visited, true, cutNode, Double.POSITIVE_INFINITY);
        extendEnd(graph, reversed(core.getFirst()), steps, visited, false, cutNode, Double.POSITIVE_INFINITY);
        return of(graph, List.copyOf(steps));
    }

    /**
     * Extends by at least the requested distances outside a committed core, using whole segments.
     * Repeated segments are allowed. A boundary means an end or ambiguous continuation, not a budget limit.
     */
    public static Extension extend(TrackGraph graph, List<TrackConnection> core, double before, double after) {
        if (core.isEmpty()) throw new IllegalArgumentException("Route core must not be empty");
        if (!Double.isFinite(before) || before < 0 || !Double.isFinite(after) || after < 0) {
            throw new IllegalArgumentException("Extension distances must be finite and nonnegative");
        }
        if (!TrackTransitionResolver.isRouteEnabled(graph, core)) throw new IllegalArgumentException("Route core contains a closed assembly path");
        var steps = new ArrayDeque<>(core);
        var end = extendEnd(graph, core.getLast(), steps, null, true, null, after);
        var start = extendEnd(graph, reversed(core.getFirst()), steps, null, false, null, before);
        return new Extension(of(graph, List.copyOf(steps)), start.count(), start.boundary(), end.boundary());
    }

    private static Growth extendEnd(TrackGraph graph, TrackConnection current, ArrayDeque<TrackConnection> steps,
                                     Set<UUID> visited, boolean append, UUID cutNode, double distance) {
        var owner = graph.assemblyForSegment(current.segmentId());
        var run = new ArrayList<TrackConnection>();
        if (owner != null) {
            var iterator = append ? steps.descendingIterator() : steps.iterator();
            while (iterator.hasNext()) {
                var step = iterator.next();
                if (graph.assemblyForSegment(step.segmentId()) != owner) break;
                run.add(append ? step : reversed(step));
            }
            java.util.Collections.reverse(run);
        }
        double length = 0;
        int count = 0;
        while (length < distance) {
            var segment = graph.segment(current.segmentId());
            UUID exit = current.fromStart() ? segment.endNodeId() : segment.startNodeId();
            if (exit.equals(cutNode)) return new Growth(count, true);
            TrackConnection next = null;
            for (var candidate : graph.connections(exit)) {
                if (candidate.segmentId().equals(current.segmentId())) continue;
                if (!TrackTransitionResolver.permits(graph, current, candidate)) continue;
                if (owner != null && graph.assemblyForSegment(candidate.segmentId()) == owner) {
                    var continued = new ArrayList<>(run);
                    continued.add(candidate);
                    if (!TrackTransitionResolver.isSequenceEnabled(owner, continued)) continue;
                }
                // Count all choices before checking visited IDs: a branch must never select a side.
                if (next != null) return new Growth(count, true);
                next = candidate;
            }
            if (next == null || (visited != null && !visited.add(next.segmentId()))) return new Growth(count, true);
            if (append) steps.addLast(next);
            else steps.addFirst(reversed(next));
            double extended = length + graph.segment(next.segmentId()).geometry().getLength();
            if (!Double.isFinite(extended) || extended <= length) {
                throw new IllegalArgumentException("Extension length must be finite and strictly increasing");
            }
            length = extended;
            count++;
            var nextOwner = graph.assemblyForSegment(next.segmentId());
            if (nextOwner != owner) { run.clear(); owner = nextOwner; }
            if (owner != null) run.add(next);
            current = next;
        }
        return new Growth(count, false);
    }

    public List<TrackConnection> connections() {
        return connections;
    }

    /** Total three-dimensional centerline length, in blocks. */
    public double getLength() {
        return distances[geometries.size()];
    }

    /** Length of one directed segment occurrence, for converting segment-local anchors. */
    public double segmentLength(int segmentIndex) {
        return geometries.get(segmentIndex).getLength();
    }

    /** Copies a non-empty contiguous range, sharing immutable geometry and rebuilding local distances. */
    public TrackRoute slice(int fromInclusive, int toExclusive) {
        if (fromInclusive < 0 || toExclusive > connections.size() || fromInclusive >= toExclusive) {
            throw new IllegalArgumentException("Route slice must be a non-empty segment range");
        }
        if (fromInclusive == 0 && toExclusive == connections.size()) return this;
        return new TrackRoute(connections.subList(fromInclusive, toExclusive), geometries.subList(fromInclusive, toExclusive));
    }

    /** Reverses order and direction without consulting the graph or rebuilding geometry caches. */
    public TrackRoute reversed() {
        var steps = new ArrayList<TrackConnection>(connections.size());
        var reversedGeometries = new ArrayList<TrackGeometry>(geometries.size());
        for (int i = connections.size() - 1; i >= 0; i--) {
            steps.add(reversed(connections.get(i)));
            reversedGeometries.add(geometries.get(i).reversed());
        }
        return new TrackRoute(steps, reversedGeometries);
    }

    private static TrackConnection reversed(TrackConnection connection) {
        return new TrackConnection(connection.segmentId(), !connection.fromStart());
    }

    TrackGeometry geometry(int segmentIndex) {
        return geometries.get(segmentIndex);
    }

    double startDistance(int segmentIndex) {
        return distances[segmentIndex];
    }
}
