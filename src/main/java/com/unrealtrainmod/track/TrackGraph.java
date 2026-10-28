/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** A single dimension's graph. All access and mutation belong to its owning server thread. */
public final class TrackGraph {
    private final Map<UUID, TrackNode> nodes = new LinkedHashMap<>();
    private final Map<UUID, TrackSegment> segments = new LinkedHashMap<>();
    private final Map<UUID, List<TrackConnection>> connections = new HashMap<>();
    private final Map<Long, Set<UUID>> chunks = new HashMap<>();
    private final Runnable onChange;
    private final List<Consumer<TrackSegmentChange>> segmentListeners = new ArrayList<>();

    public TrackGraph() {
        this(() -> {});
    }

    public TrackGraph(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange);
    }

    public TrackNode addNode(UUID id, TrackVector position, double gauge) {
        TrackNode node = new TrackNode(id, position, gauge);
        if (nodes.containsKey(id)) throw new TrackGraphException(TrackGraphException.Reason.DUPLICATE_ID);
        insertNode(node);
        onChange.run();
        return node;
    }

    private void insertNode(TrackNode node) {
        nodes.put(node.id(), node);
        connections.put(node.id(), new ArrayList<>());
    }

    public TrackSegment addStraight(UUID id, UUID startNodeId, UUID endNodeId) {
        return addSegment(id, startNodeId, endNodeId, null, null);
    }

    /** Validates the entire placement before publishing any new node, index entry, or notification. */
    public TrackSegment addStraight(UUID id, TrackPlacementEndpoint start, TrackPlacementEndpoint end, double gauge) {
        TrackNode a = placementNode(start, gauge), b = placementNode(end, gauge);
        if ((start.nodeId() == null && nodes.containsKey(a.id()))
                || (end.nodeId() == null && nodes.containsKey(b.id()))) {
            throw new TrackGraphException(TrackGraphException.Reason.DUPLICATE_ID);
        }
        TrackSegment segment = prepareSegment(id, a, b, null, null);
        if (start.nodeId() == null) insertNode(a);
        if (end.nodeId() == null) insertNode(b);
        return insertSegment(segment);
    }

    private TrackNode placementNode(TrackPlacementEndpoint endpoint, double gauge) {
        TrackNode result = endpoint.nodeId() == null
                ? new TrackNode(UUID.randomUUID(), endpoint.position(), gauge) : node(endpoint.nodeId());
        if (result.gauge() != gauge) throw new TrackGraphException(TrackGraphException.Reason.GAUGE_MISMATCH);
        return result;
    }

    public TrackSegment addBezier(UUID id, UUID startNodeId, UUID endNodeId,
                                  TrackPoint control1, TrackPoint control2) {
        return addSegment(id, startNodeId, endNodeId,
                Objects.requireNonNull(control1), Objects.requireNonNull(control2));
    }

    private TrackSegment addSegment(UUID id, UUID startNodeId, UUID endNodeId,
                                    TrackPoint control1, TrackPoint control2) {
        return insertSegment(prepareSegment(id, node(startNodeId), node(endNodeId), control1, control2));
    }

    private TrackSegment prepareSegment(UUID id, TrackNode start, TrackNode end,
                                        TrackPoint control1, TrackPoint control2) {
        Objects.requireNonNull(id);
        if (segments.containsKey(id)) throw new TrackGraphException(TrackGraphException.Reason.DUPLICATE_ID);
        if (start.id().equals(end.id())) throw new TrackGraphException(TrackGraphException.Reason.SAME_NODE);
        if (start.gauge() != end.gauge()) throw new TrackGraphException(TrackGraphException.Reason.GAUGE_MISMATCH);
        TrackPoint a = new TrackPoint(start.position().x(), start.position().z());
        TrackPoint b = new TrackPoint(end.position().x(), end.position().z());
        LinearGrade grade = new LinearGrade(start.position().y(), end.position().y());
        TrackChunkBounds bounds = control1 == null ? TrackChunkBounds.of(a, b)
                : TrackChunkBounds.of(a, control1, control2, b);
        TrackGeometry geometry = control1 == null ? new StraightTrackGeometry(a, b, grade, start.gauge())
                : new BezierTrackGeometry(a, control1, control2, b, grade, start.gauge());
        return new TrackSegment(id, start.id(), end.id(), geometry, bounds);
    }

    private TrackSegment insertSegment(TrackSegment segment) {
        // Every operation that can reject user input has completed before any index is changed.
        UUID id = segment.id();
        segments.put(id, segment);
        connections.get(segment.startNodeId()).add(new TrackConnection(id, true));
        connections.get(segment.endNodeId()).add(new TrackConnection(id, false));
        segment.chunkBounds().forEachChunk(key -> chunks.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(id));
        onChange.run();
        notifySegmentChange(new TrackSegmentChange(TrackSegmentChange.Type.ADDED, segment));
        return segment;
    }

    public void removeSegment(UUID id) {
        TrackSegment segment = segment(id);
        segments.remove(id);
        connections.get(segment.startNodeId()).removeIf(connection -> connection.segmentId().equals(id));
        connections.get(segment.endNodeId()).removeIf(connection -> connection.segmentId().equals(id));
        segment.chunkBounds().forEachChunk(key -> {
            Set<UUID> indexed = chunks.get(key);
            indexed.remove(id);
            if (indexed.isEmpty()) chunks.remove(key);
        });
        onChange.run();
        notifySegmentChange(new TrackSegmentChange(TrackSegmentChange.Type.REMOVED, segment));
    }

    public void addSegmentListener(Consumer<TrackSegmentChange> listener) {
        segmentListeners.add(Objects.requireNonNull(listener));
    }

    public void removeSegmentListener(Consumer<TrackSegmentChange> listener) {
        segmentListeners.remove(listener);
    }

    private void notifySegmentChange(TrackSegmentChange change) {
        for (var listener : segmentListeners) listener.accept(change);
    }

    public void removeNode(UUID id) {
        node(id);
        if (!connections.get(id).isEmpty()) throw new TrackGraphException(TrackGraphException.Reason.NODE_CONNECTED);
        nodes.remove(id);
        connections.remove(id);
        onChange.run();
    }

    public TrackNode node(UUID id) {
        TrackNode node = nodes.get(id);
        if (node == null) throw new TrackGraphException(TrackGraphException.Reason.UNKNOWN_NODE);
        return node;
    }

    public TrackSegment segment(UUID id) {
        TrackSegment segment = segments.get(id);
        if (segment == null) throw new TrackGraphException(TrackGraphException.Reason.UNKNOWN_SEGMENT);
        return segment;
    }

    public List<TrackNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public List<TrackSegment> segments() {
        return List.copyOf(segments.values());
    }

    public List<TrackConnection> connections(UUID nodeId) {
        node(nodeId);
        return List.copyOf(connections.get(nodeId));
    }

    /** Candidates may include segments whose control bounds, but not centerlines, cross this chunk. */
    public List<TrackSegment> segmentsInChunk(int chunkX, int chunkZ) {
        Set<UUID> indexed = chunks.get(TrackChunkBounds.key(chunkX, chunkZ));
        if (indexed == null) return List.of();
        return indexed.stream().map(segments::get).toList();
    }
}
