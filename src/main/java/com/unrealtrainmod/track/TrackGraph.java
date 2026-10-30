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
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.JunctionState;
import com.unrealtrainmod.track.assembly.TrackPath;
import com.unrealtrainmod.track.assembly.TrackPort;
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
    private Consumer<List<TrackSegment>> segmentValidator = segments -> {};
    private final Map<UUID, TrackAssemblyInstance> assemblies = new LinkedHashMap<>();
    private final Map<UUID, UUID> segmentAssemblies = new HashMap<>();
    private final Map<UUID, UUID> portAssemblies = new HashMap<>();
    private final List<Consumer<TrackAssemblyInstance>> assemblyListeners = new ArrayList<>();
    private final List<Consumer<TrackAssemblyInstance>> assemblyRemoveListeners = new ArrayList<>();

    public TrackGraph() {
        this(() -> {});
    }

    public TrackGraph(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange);
    }

    public TrackNode addNode(UUID id, TrackVector position, double gauge) {
        return addNode(id, position, gauge, null);
    }

    public TrackNode addNode(UUID id, TrackVector position, double gauge, TrackCurveParameters curve) {
        TrackNode node = new TrackNode(id, position, gauge, curve);
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
        return addStraight(id, startNodeId, endNodeId, null);
    }

    public TrackSegment addStraight(UUID id, UUID startNodeId, UUID endNodeId, String modelId) {
        return addSegment(prepareSegment(id, node(startNodeId), node(endNodeId), null, null, modelId));
    }

    /** Validates the entire placement before publishing any new node, index entry, or notification. */
    public TrackSegment addStraight(UUID id, TrackPlacementEndpoint start, TrackPlacementEndpoint end, double gauge) {
        return addStraight(id, start, end, gauge, null);
    }

    public TrackSegment addStraight(UUID id, TrackPlacementEndpoint start, TrackPlacementEndpoint end, double gauge, String modelId) {
        return addPlacement(id, start, end, gauge, modelId, null, null, false, false);
    }

    public TrackSegment addPlacement(UUID id, TrackPlacementEndpoint start, TrackPlacementEndpoint end, double gauge,
                                     String modelId, TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                     boolean startEdited, boolean endEdited) {
        TrackNode a = placementNode(start, gauge), b = placementNode(end, gauge);
        if ((start.nodeId() == null && nodes.containsKey(a.id()))
                || (end.nodeId() == null && nodes.containsKey(b.id()))) {
            throw new TrackGraphException(TrackGraphException.Reason.DUPLICATE_ID);
        }
        TrackGeometry geometry = TrackPlacementGeometry.create(a.position(), b.position(), gauge, startCurve, endCurve);
        if (geometry.getLength() > TrackPlacementGeometry.MAX_LENGTH) {
            throw new IllegalArgumentException("Construction track exceeds 256 blocks");
        }
        TrackPoint control1 = geometry instanceof BezierTrackGeometry bezier ? bezier.control1() : null;
        TrackPoint control2 = geometry instanceof BezierTrackGeometry bezier ? bezier.control2() : null;
        TrackSegment segment = prepareSegment(id, a, b, control1, control2, modelId, geometry);
        validateExternalSegment(segment);
        segmentValidator.accept(List.of(segment));
        TrackNode updatedA = start.nodeId() == null || startEdited
                ? new TrackNode(a.id(), a.position(), gauge, startCurve) : a;
        TrackNode updatedB = end.nodeId() == null || endEdited
                ? new TrackNode(b.id(), b.position(), gauge, endCurve) : b;
        if (start.nodeId() == null) insertNode(updatedA);
        else if (startEdited) nodes.put(a.id(), updatedA);
        if (end.nodeId() == null) insertNode(updatedB);
        else if (endEdited) nodes.put(b.id(), updatedB);
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
        return addBezier(id, startNodeId, endNodeId, control1, control2, null);
    }

    public TrackSegment addBezier(UUID id, UUID startNodeId, UUID endNodeId,
                                  TrackPoint control1, TrackPoint control2, String modelId) {
        return addSegment(prepareSegment(id, node(startNodeId), node(endNodeId),
                Objects.requireNonNull(control1), Objects.requireNonNull(control2), modelId));
    }

    private TrackSegment prepareSegment(UUID id, TrackNode start, TrackNode end,
                                        TrackPoint control1, TrackPoint control2, String modelId) {
        return prepareSegment(id, start, end, control1, control2, modelId, null);
    }

    private TrackSegment prepareSegment(UUID id, TrackNode start, TrackNode end,
                                        TrackPoint control1, TrackPoint control2, String modelId, TrackGeometry preparedGeometry) {
        Objects.requireNonNull(id);
        if (modelId != null && modelId.isBlank()) throw new IllegalArgumentException("Blank track model ID");
        if (segments.containsKey(id)) throw new TrackGraphException(TrackGraphException.Reason.DUPLICATE_ID);
        if (start.id().equals(end.id())) throw new TrackGraphException(TrackGraphException.Reason.SAME_NODE);
        if (start.gauge() != end.gauge()) throw new TrackGraphException(TrackGraphException.Reason.GAUGE_MISMATCH);
        TrackPoint a = new TrackPoint(start.position().x(), start.position().z());
        TrackPoint b = new TrackPoint(end.position().x(), end.position().z());
        TrackChunkBounds bounds = control1 == null ? TrackChunkBounds.of(a, b)
                : TrackChunkBounds.of(a, control1, control2, b);
        TrackGeometry geometry = preparedGeometry;
        if (geometry == null) {
            LinearGrade grade = new LinearGrade(start.position().y(), end.position().y());
            geometry = control1 == null ? new StraightTrackGeometry(a, b, grade, start.gauge())
                    : new BezierTrackGeometry(a, control1, control2, b, grade, start.gauge());
        }
        return new TrackSegment(id, start.id(), end.id(), geometry, bounds, modelId);
    }

    private TrackSegment addSegment(TrackSegment segment) {
        validateExternalSegment(segment);
        segmentValidator.accept(List.of(segment));
        return insertSegment(segment);
    }

    private void validateExternalSegment(TrackSegment segment) {
        for (UUID port : List.of(segment.startNodeId(), segment.endNodeId())) {
            var assembly = assemblyAtPort(port);
            if (assembly == null) continue;
            long external = connections(port).stream().filter(c -> !assembly.segments().contains(c.segmentId())).count();
            if (external != 0) throw new IllegalArgumentException("Turnout port already has an external track");
            validatePortTangent(segment, port, assembly);
        }
    }

    private static void validatePortTangent(TrackSegment segment, UUID port, TrackAssemblyInstance assembly) {
        boolean start = segment.startNodeId().equals(port);
        var tangent = segment.geometry().sample(start ? 0 : segment.geometry().getLength()).tangent();
        double sign = start ? 1 : -1;
        double yaw = assembly.portAt(port).outwardYaw();
        double radians = Math.toRadians(yaw);
        double dot = sign * (-Math.sin(radians) * tangent.x() + Math.cos(radians) * tangent.z());
        if (Math.abs(tangent.y()) > 1e-6 || dot < Math.cos(Math.toRadians(1))) {
            throw new IllegalArgumentException("External track must meet the flat turnout port within one degree");
        }
    }

    private TrackSegment insertSegment(TrackSegment segment) {
        // Every operation that can reject user input has completed before any index is changed.
        indexSegment(segment);
        onChange.run();
        notifySegmentChange(new TrackSegmentChange(TrackSegmentChange.Type.ADDED, segment));
        return segment;
    }

    private void indexSegment(TrackSegment segment) {
        UUID id = segment.id();
        segments.put(id, segment);
        connections.get(segment.startNodeId()).add(new TrackConnection(id, true));
        connections.get(segment.endNodeId()).add(new TrackConnection(id, false));
        segment.chunkBounds().forEachChunk(key -> chunks.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(id));
    }

    public void removeSegment(UUID id) {
        var assembly = assemblyForSegment(id);
        if (assembly != null) { removeAssembly(assembly.id()); return; }
        TrackSegment segment = segment(id);
        unindexSegment(segment);
        onChange.run();
        notifySegmentChange(new TrackSegmentChange(TrackSegmentChange.Type.REMOVED, segment));
    }

    private void unindexSegment(TrackSegment segment) {
        UUID id = segment.id();
        segments.remove(id);
        connections.get(segment.startNodeId()).removeIf(connection -> connection.segmentId().equals(id));
        connections.get(segment.endNodeId()).removeIf(connection -> connection.segmentId().equals(id));
        for (UUID nodeId : List.of(segment.startNodeId(), segment.endNodeId())) {
            if (connections.get(nodeId).isEmpty()) {
                nodes.remove(nodeId);
                connections.remove(nodeId);
            }
        }
        segment.chunkBounds().forEachChunk(key -> {
            Set<UUID> indexed = chunks.get(key);
            indexed.remove(id);
            if (indexed.isEmpty()) chunks.remove(key);
        });
    }

    public void addSegmentListener(Consumer<TrackSegmentChange> listener) {
        segmentListeners.add(Objects.requireNonNull(listener));
    }

    /** Runs before any placement node or segment is committed. Installed after saved graph restoration. */
    public void setSegmentValidator(Consumer<List<TrackSegment>> validator) {
        segmentValidator = Objects.requireNonNull(validator);
    }

    public void removeSegmentListener(Consumer<TrackSegmentChange> listener) {
        segmentListeners.remove(listener);
    }

    private void notifySegmentChange(TrackSegmentChange change) {
        for (var listener : segmentListeners) listener.accept(change);
    }

    public List<TrackAssemblyInstance> assemblies() { return List.copyOf(assemblies.values()); }
    public TrackAssemblyInstance assembly(UUID id) {
        var value = assemblies.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown turnout: " + id);
        return value;
    }
    public TrackAssemblyInstance assemblyForSegment(UUID id) { return assemblies.get(segmentAssemblies.get(id)); }
    public TrackAssemblyInstance assemblyAtPort(UUID id) { return assemblies.get(portAssemblies.get(id)); }
    public void addAssemblyListener(Consumer<TrackAssemblyInstance> listener) { assemblyListeners.add(listener); }
    public void removeAssemblyListener(Consumer<TrackAssemblyInstance> listener) { assemblyListeners.remove(listener); }
    public void addAssemblyRemoveListener(Consumer<TrackAssemblyInstance> listener) { assemblyRemoveListeners.add(listener); }
    public void removeAssemblyRemoveListener(Consumer<TrackAssemblyInstance> listener) { assemblyRemoveListeners.remove(listener); }

    public TrackAssemblyInstance createTurnout(UUID originId, TrackAssemblyDefinition definition, double yaw) {
        TrackNode origin = node(originId);
        return createTurnout(new TrackPlacementEndpoint(originId, origin.position()), origin.gauge(), definition, yaw);
    }

    /** Pure preparation: all topology checks run against unpublished nodes and segments. */
    public PreparedAssembly prepareTurnout(TrackPlacementEndpoint origin, double gauge,
                                            TrackAssemblyDefinition definition, double yaw) {
        TrackNode a = placementNode(origin, gauge);
        if (origin.nodeId() != null && (assemblyAtPort(a.id()) != null || connections(a.id()).size() > 1)) {
            throw new IllegalArgumentException("Turnout origin must be a free endpoint");
        }
        if (!Double.isFinite(yaw)) throw new IllegalArgumentException("Invalid turnout yaw");
        yaw = ((yaw % 360) + 540) % 360 - 180;
        var geometry = definition.geometry(a.position(), yaw, gauge);
        var preparedNodes = new LinkedHashMap<UUID, TrackNode>();
        var ports = new LinkedHashMap<String, TrackPort>();
        for (var entry : geometry.ports().entrySet()) {
            var port = entry.getValue();
            UUID id = entry.getKey().equals("A") ? a.id() : UUID.randomUUID();
            ports.put(entry.getKey(), new TrackPort(id, port.outwardYaw()));
            double handle = geometry.paths().entrySet().stream()
                    .filter(path -> definition.routes().get(path.getKey()).entrance().equals(entry.getKey())
                            || definition.routes().get(path.getKey()).exit().equals(entry.getKey()))
                    .mapToDouble(path -> path.getValue().stream().mapToDouble(TrackGeometry::getLength).sum()).min().orElseThrow() / 3;
            preparedNodes.put(id, new TrackNode(id, port.position(), gauge,
                    new TrackCurveParameters(entry.getKey().equals("A") ? yaw : port.outwardYaw(), handle)));
        }
        var batch = new ArrayList<TrackSegment>();
        var paths = new LinkedHashMap<String, TrackPath>();
        for (var entry : geometry.paths().entrySet()) {
            var route = definition.routes().get(entry.getKey());
            var parts = entry.getValue();
            var steps = new ArrayList<TrackConnection>();
            var previous = preparedNodes.get(ports.get(route.entrance()).nodeId());
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                TrackNode next;
                if (i == parts.size() - 1) next = preparedNodes.get(ports.get(route.exit()).nodeId());
                else {
                    next = new TrackNode(UUID.randomUUID(), part.sample(part.getLength()).position(), gauge);
                    preparedNodes.put(next.id(), next);
                }
                var curve = part instanceof BezierTrackGeometry bezier ? bezier : null;
                var segment = prepareSegment(UUID.randomUUID(), previous, next,
                        curve == null ? null : curve.control1(), curve == null ? null : curve.control2(), null, part);
                batch.add(segment);
                steps.add(new TrackConnection(segment.id(), true));
                previous = next;
            }
            paths.put(entry.getKey(), new TrackPath(steps));
        }
        var instance = new TrackAssemblyInstance(UUID.randomUUID(), definition, ports, paths, a.position(), yaw, gauge,
                definition.initialState(), 0, null, geometry);
        var byId = batch.stream().collect(java.util.stream.Collectors.toMap(TrackSegment::id, segment -> segment));
        for (var node : preparedNodes.values()) {
            if (!node.id().equals(a.id()) && nodes.containsKey(node.id())) {
                throw new IllegalArgumentException("Duplicate assembly node ID");
            }
        }
        validateAssembly(instance, preparedNodes::get, byId::get);
        return new PreparedAssembly(instance, List.copyOf(preparedNodes.values()), List.copyOf(batch));
    }

    public record PreparedAssembly(TrackAssemblyInstance instance, List<TrackNode> nodes, List<TrackSegment> segments) {}

    public TrackAssemblyInstance createTurnout(TrackPlacementEndpoint origin, double gauge,
                                               TrackAssemblyDefinition definition, double yaw) {
        var prepared = prepareTurnout(origin, gauge, definition, yaw);
        var instance = prepared.instance();
        segmentValidator.accept(prepared.segments());
        // All rejecting validation precedes mutation; observers see complete ownership and topology.
        for (var node : prepared.nodes()) {
            if (nodes.containsKey(node.id())) nodes.put(node.id(), node);
            else insertNode(node);
        }
        for (var segment : prepared.segments()) indexSegment(segment);
        indexAssembly(instance);
        onChange.run();
        for (var segment : prepared.segments()) notifySegmentChange(new TrackSegmentChange(TrackSegmentChange.Type.ADDED, segment));
        for (var listener : assemblyListeners) listener.accept(instance);
        return instance;
    }

    /** Called after saved nodes and segments have been restored, before observers are installed. */
    public void restoreAssembly(TrackAssemblyInstance value) {
        validateAssembly(value, this::node, this::segment);
        indexAssembly(value);
    }

    private void validateAssembly(TrackAssemblyInstance value, java.util.function.Function<UUID, TrackNode> nodeLookup,
                                  java.util.function.Function<UUID, TrackSegment> segmentLookup) {
        if (assemblies.containsKey(value.id())) throw new IllegalArgumentException("Duplicate assembly ID");
        for (var entry : value.ports().entrySet()) {
            var port = entry.getValue();
            var node = nodeLookup.apply(port.nodeId());
            if (node == null || node.gauge() != value.gauge()
                    || !samePosition(node.position(), value.geometry().ports().get(entry.getKey()).position())) {
                throw new IllegalArgumentException("Assembly port geometry mismatch");
            }
            if (portAssemblies.containsKey(port.nodeId())) throw new IllegalArgumentException("Duplicate assembly port");
            var external = connections.getOrDefault(port.nodeId(), List.of()).stream()
                    .filter(c -> !value.segments().contains(c.segmentId())).toList();
            if (external.size() > 1) throw new IllegalArgumentException("Too many assembly connections");
            for (var connection : external) validatePortTangent(segment(connection.segmentId()), port.nodeId(), value);
        }
        var portIds = value.ports().values().stream().map(TrackPort::nodeId).collect(java.util.stream.Collectors.toSet());
        for (var entry : value.paths().entrySet()) {
            var route = value.definition().routes().get(entry.getKey());
            UUID previous = value.ports().get(route.entrance()).nodeId();
            var expected = value.geometry().paths().get(entry.getKey());
            var steps = entry.getValue().steps();
            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                var segment = segmentLookup.apply(step.segmentId());
                if (segment == null || segmentAssemblies.containsKey(segment.id())) {
                    throw new IllegalArgumentException("Missing or already owned assembly segment");
                }
                UUID entrance = step.fromStart() ? segment.startNodeId() : segment.endNodeId();
                UUID exit = step.fromStart() ? segment.endNodeId() : segment.startNodeId();
                if (!entrance.equals(previous)) throw new IllegalArgumentException("Discontinuous assembly path");
                var startNode = nodeLookup.apply(entrance);
                var endNode = nodeLookup.apply(exit);
                var part = expected.get(i);
                if (startNode == null || endNode == null || startNode.gauge() != value.gauge() || endNode.gauge() != value.gauge()
                        || !samePosition(startNode.position(), part.sample(0).position())
                        || !samePosition(endNode.position(), part.sample(part.getLength()).position())
                        || Math.abs(segment.geometry().getLength() - part.getLength()) > 1e-6) {
                    throw new IllegalArgumentException("Assembly path geometry mismatch");
                }
                if (part instanceof BezierTrackGeometry wanted) {
                    if (!(segment.geometry() instanceof BezierTrackGeometry actual)
                            || !samePoint(step.fromStart() ? actual.control1() : actual.control2(), wanted.control1())
                            || !samePoint(step.fromStart() ? actual.control2() : actual.control1(), wanted.control2())) {
                        throw new IllegalArgumentException("Assembly curve mismatch");
                    }
                } else if (segment.type() != TrackSegment.Type.STRAIGHT) throw new IllegalArgumentException("Assembly line mismatch");
                if (i < steps.size() - 1 && portIds.contains(exit)) throw new IllegalArgumentException("Path crosses an external port");
                for (UUID id : List.of(entrance, exit)) {
                    if (!portIds.contains(id) && (portAssemblies.containsKey(id)
                            || connections.getOrDefault(id, List.of()).stream().anyMatch(c -> !value.segments().contains(c.segmentId())))) {
                        throw new IllegalArgumentException("Assembly internal node has an external connection");
                    }
                }
                previous = exit;
            }
            if (!previous.equals(value.ports().get(route.exit()).nodeId())) throw new IllegalArgumentException("Wrong assembly path exit");
        }
    }

    private void indexAssembly(TrackAssemblyInstance value) {
        assemblies.put(value.id(), value);
        value.ports().values().forEach(port -> portAssemblies.put(port.nodeId(), value.id()));
        value.segments().forEach(id -> segmentAssemblies.put(id, value.id()));
    }

    private static boolean samePosition(TrackVector a, TrackVector b) {
        return Math.abs(a.x() - b.x()) <= 1e-6 && Math.abs(a.y() - b.y()) <= 1e-6 && Math.abs(a.z() - b.z()) <= 1e-6;
    }

    private static boolean samePoint(TrackPoint a, TrackPoint b) {
        return Math.abs(a.x() - b.x()) <= 1e-6 && Math.abs(a.z() - b.z()) <= 1e-6;
    }

    public void setAssemblyState(UUID id, JunctionState state) {
        var old = assembly(id);
        if (old.state().equals(state)) return;
        if (old.occupant() != null) throw new IllegalArgumentException("Turnout occupied by " + old.occupant());
        var updated = old.withState(state);
        assemblies.put(id, updated);
        onChange.run();
        for (var listener : assemblyListeners) listener.accept(updated);
    }

    public void setAssemblyOccupant(UUID id, UUID occupant) {
        var old = assembly(id);
        if (Objects.equals(old.occupant(), occupant)) return;
        assemblies.put(id, old.withOccupant(occupant));
        onChange.run();
    }

    public void removeAssembly(UUID id) {
        var value = assembly(id);
        var removed = value.segments().stream().map(this::segment).toList();
        assemblies.remove(id);
        value.ports().values().forEach(port -> portAssemblies.remove(port.nodeId()));
        value.segments().forEach(segmentAssemblies::remove);
        for (var segment : removed) unindexSegment(segment);
        onChange.run();
        for (var listener : assemblyRemoveListeners) listener.accept(value);
        for (var segment : removed) notifySegmentChange(new TrackSegmentChange(TrackSegmentChange.Type.REMOVED, segment));
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
