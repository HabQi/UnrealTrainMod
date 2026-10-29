/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.path;

import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.geometry.TrackGeometry;
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
        var geometries = new ArrayList<TrackGeometry>(steps.size());
        UUID previousExit = null;
        for (int i = 0; i < steps.size(); i++) {
            var connection = steps.get(i);
            var segment = graph.segment(connection.segmentId());
            UUID entrance = connection.fromStart() ? segment.startNodeId() : segment.endNodeId();
            if (previousExit != null && !previousExit.equals(entrance)) {
                throw new IllegalArgumentException("Route segments " + (i - 1) + " and " + i + " do not share an exit/entrance node");
            }
            previousExit = connection.fromStart() ? segment.endNodeId() : segment.startNodeId();
            var geometry = segment.geometry();
            geometries.add(connection.fromStart() ? geometry : geometry.reversed());
        }
        return new TrackRoute(steps, geometries);
    }

    /**
     * Extends both ends through unique connections, stopping at ends, branches or visited segments.
     * A ring starts at the seed's entrance, contains each segment once, and has no distance wrapping.
     */
    public static TrackRoute trace(TrackGraph graph, TrackConnection seed) {
        var steps = new ArrayDeque<TrackConnection>();
        var visited = new HashSet<UUID>();
        steps.add(seed);
        visited.add(seed.segmentId());
        extend(graph, seed, steps, visited, true);
        extend(graph, reversed(seed), steps, visited, false);
        return of(graph, List.copyOf(steps));
    }

    private static void extend(TrackGraph graph, TrackConnection current, ArrayDeque<TrackConnection> steps,
                               Set<UUID> visited, boolean append) {
        while (true) {
            var segment = graph.segment(current.segmentId());
            UUID exit = current.fromStart() ? segment.endNodeId() : segment.startNodeId();
            TrackConnection next = null;
            for (var candidate : graph.connections(exit)) {
                if (candidate.segmentId().equals(current.segmentId())) continue;
                // Count all choices before checking visited IDs: a branch must never select a side.
                if (next != null) return;
                next = candidate;
            }
            if (next == null || !visited.add(next.segmentId())) return;
            if (append) steps.addLast(next);
            else steps.addFirst(reversed(next));
            current = next;
        }
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
