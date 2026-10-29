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
        double yaw = port.equals(assembly.a()) ? assembly.yaw() + 180
                : port.equals(assembly.b()) ? assembly.yaw() : assembly.definition().exitYaw(assembly.yaw());
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

    /** Pure preparation: no graph changes, world validation, or observer notifications. */
    public PreparedTurnout prepareTurnout(TrackPlacementEndpoint origin, double gauge,
                                          TrackAssemblyDefinition definition, double yaw) {
        TrackNode a = placementNode(origin, gauge);
        if (origin.nodeId() != null && (assemblyAtPort(a.id()) != null || connections(a.id()).size() > 1)) {
            throw new IllegalArgumentException("Turnout origin must be a free endpoint");
        }
        var instance = new TrackAssemblyInstance(UUID.randomUUID(), definition, a.id(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), a.position(), yaw, a.gauge(), TrackAssemblyInstance.State.STRAIGHT, 0, null);
        var geometry = instance.geometry();
        if (origin.nodeId() != null) {
            for (var connection : connections(a.id())) validatePortTangent(segment(connection.segmentId()), a.id(), instance);
        }
        var b = new TrackNode(instance.b(), geometry.b(), a.gauge(), new TrackCurveParameters(yaw, geometry.straight().getLength() / 3));
        var c = new TrackNode(instance.c(), geometry.c(), a.gauge(), new TrackCurveParameters(definition.exitYaw(yaw),
                geometry.diverging().getLength() / 3));
        var direct = prepareSegment(instance.straight(), a, b, null, null, null, geometry.straight());
        var branch = prepareSegment(instance.diverging(), a, c, geometry.control1(), geometry.control2(), null, geometry.diverging());
        return new PreparedTurnout(instance, a, b, c, List.of(direct, branch));
    }

    public record PreparedTurnout(TrackAssemblyInstance instance, TrackNode a, TrackNode b, TrackNode c,
                                  List<TrackSegment> segments) {}

    public TrackAssemblyInstance createTurnout(TrackPlacementEndpoint origin, double gauge,
                                               TrackAssemblyDefinition definition, double yaw) {
        var prepared = prepareTurnout(origin, gauge, definition, yaw);
        var instance = prepared.instance();
        var a = prepared.a();
        var batch = prepared.segments();
        segmentValidator.accept(batch);
        // No observer sees partial topology or missing ownership, including during block updates.
        if (origin.nodeId() == null) insertNode(a);
        insertNode(prepared.b()); insertNode(prepared.c());
        nodes.put(a.id(), new TrackNode(a.id(), a.position(), a.gauge(),
                new TrackCurveParameters(yaw, instance.geometry().straight().getLength() / 3)));
        for (var segment : batch) indexSegment(segment);
        restoreAssembly(instance);
        onChange.run();
        for (var segment : batch) notifySegmentChange(new TrackSegmentChange(TrackSegmentChange.Type.ADDED, segment));
        for (var listener : assemblyListeners) listener.accept(instance);
        return instance;
    }

    /** Called after all saved segments have been restored, before runtime observers are installed. */
    public void restoreAssembly(TrackAssemblyInstance value) {
        if (assemblies.containsKey(value.id())) throw new IllegalArgumentException("Duplicate turnout ID");
        var geometry = value.geometry();
        if (!samePosition(node(value.a()).position(), value.origin()) || !samePosition(node(value.b()).position(), geometry.b())
                || !samePosition(node(value.c()).position(), geometry.c()) || segment(value.straight()).type() != TrackSegment.Type.STRAIGHT
                || !(segment(value.diverging()).geometry() instanceof BezierTrackGeometry curve)
                || !samePoint(curve.control1(), geometry.control1()) || !samePoint(curve.control2(), geometry.control2())) {
            throw new IllegalArgumentException("Turnout geometry does not match its definition");
        }
        for (UUID port : value.ports()) {
            if (node(port).gauge() != value.gauge()) throw new IllegalArgumentException("Turnout gauge mismatch");
            if (portAssemblies.containsKey(port)) throw new IllegalArgumentException("Duplicate turnout port");
            var external = connections(port).stream().filter(c -> !value.segments().contains(c.segmentId())).toList();
            if (external.size() > 1) throw new IllegalArgumentException("Too many turnout connections");
            for (var connection : external) validatePortTangent(segment(connection.segmentId()), port, value);
        }
        for (UUID id : value.segments()) {
            var segment = segment(id);
            UUID end = id.equals(value.straight()) ? value.b() : value.c();
            if (!segment.startNodeId().equals(value.a()) || !segment.endNodeId().equals(end)
                    || segmentAssemblies.containsKey(id)) throw new IllegalArgumentException("Invalid turnout path");
        }
        assemblies.put(value.id(), value);
        value.ports().forEach(id -> portAssemblies.put(id, value.id()));
        value.segments().forEach(id -> segmentAssemblies.put(id, value.id()));
    }

    private static boolean samePosition(TrackVector a, TrackVector b) {
        return Math.abs(a.x() - b.x()) <= 1e-6 && Math.abs(a.y() - b.y()) <= 1e-6 && Math.abs(a.z() - b.z()) <= 1e-6;
    }

    private static boolean samePoint(TrackPoint a, TrackPoint b) {
        return Math.abs(a.x() - b.x()) <= 1e-6 && Math.abs(a.z() - b.z()) <= 1e-6;
    }

    public void setAssemblyState(UUID id, TrackAssemblyInstance.State state) {
        var old = assembly(id);
        if (old.state() == state) return;
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
        value.ports().forEach(portAssemblies::remove);
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
