/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.geometry.VerticalTrackGeometry;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrailingPass;
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
    private final Map<UUID, Set<UUID>> portAssemblies = new HashMap<>();
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
        return addNode(id, position, gauge, curve, null);
    }

    public TrackNode addNode(UUID id, TrackVector position, double gauge, TrackCurveParameters curve,
                             TrackVerticalCurveParameters verticalCurve) {
        TrackNode node = new TrackNode(id, position, gauge, curve, verticalCurve);
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
        return addPlacement(id, start, end, gauge, modelId, startCurve, endCurve, startEdited, endEdited,
                null, null, false, false);
    }

    public TrackSegment addPlacement(UUID id, TrackPlacementEndpoint start, TrackPlacementEndpoint end, double gauge,
                                     String modelId, TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                     boolean startEdited, boolean endEdited,
                                     TrackVerticalCurveParameters startVertical, TrackVerticalCurveParameters endVertical,
                                     boolean startVerticalEdited, boolean endVerticalEdited) {
        return addPlacement(id, start, end, gauge, modelId, startCurve, endCurve, startEdited, endEdited,
                startVertical, endVertical, startVerticalEdited, endVerticalEdited, false, false);
    }

    public TrackSegment addPlacement(UUID id, TrackPlacementEndpoint start, TrackPlacementEndpoint end, double gauge,
                                     String modelId, TrackCurveParameters startCurve, TrackCurveParameters endCurve,
                                     boolean startEdited, boolean endEdited,
                                     TrackVerticalCurveParameters startVertical, TrackVerticalCurveParameters endVertical,
                                     boolean startVerticalEdited, boolean endVerticalEdited, boolean startDirected, boolean endDirected) {
        TrackNode a = placementNode(start, gauge), b = placementNode(end, gauge);
        if ((start.nodeId() == null && nodes.containsKey(a.id()))
                || (end.nodeId() == null && nodes.containsKey(b.id()))) {
            throw new TrackGraphException(TrackGraphException.Reason.DUPLICATE_ID);
        }
        TrackGeometry geometry = TrackPlacementGeometry.create(a.position(), b.position(), gauge, startCurve, endCurve,
                startVertical, endVertical, startDirected, endDirected);
        if (geometry.getLength() > TrackPlacementGeometry.MAX_LENGTH) {
            throw new IllegalArgumentException("Construction track exceeds 256 blocks");
        }
        var controls = TrackGeometryDefinition.from(geometry).controls();
        TrackPoint control1 = controls.isEmpty() ? null : controls.get(0);
        TrackPoint control2 = controls.isEmpty() ? null : controls.get(1);
        TrackSegment segment = prepareSegment(id, a, b, control1, control2, modelId, geometry);
        validateExternalSegment(segment);
        segmentValidator.accept(List.of(segment));
        TrackNode updatedA = new TrackNode(a.id(), a.position(), gauge,
                start.nodeId() == null || startEdited ? startCurve : a.curve(),
                start.nodeId() == null || startVerticalEdited ? startVertical : a.verticalCurve());
        TrackNode updatedB = new TrackNode(b.id(), b.position(), gauge,
                end.nodeId() == null || endEdited ? endCurve : b.curve(),
                end.nodeId() == null || endVerticalEdited ? endVertical : b.verticalCurve());
        if (start.nodeId() == null) insertNode(updatedA);
        else if (startEdited || startVerticalEdited) nodes.put(a.id(), updatedA);
        if (end.nodeId() == null) insertNode(updatedB);
        else if (endEdited || endVerticalEdited) nodes.put(b.id(), updatedB);
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

    public TrackSegment addDefined(UUID id, UUID startNodeId, UUID endNodeId,
                                    TrackGeometryDefinition definition, String modelId) {
        var start = node(startNodeId);
        var end = node(endNodeId);
        var controls = definition.controls();
        return addSegment(prepareSegment(id, start, end,
                controls.isEmpty() ? null : controls.get(0), controls.isEmpty() ? null : controls.get(1), modelId,
                definition.create(start.position(), end.position(), start.gauge())));
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
            geometry = new TrackGeometryDefinition(control1 == null ? List.of() : List.of(control1, control2), List.of())
                    .create(start.position(), end.position(), start.gauge());
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
            if (nodes.containsKey(port) && isAssemblyInternalNode(port)) throw new IllegalArgumentException("Cannot connect to an assembly's internal node");
            if (!assembliesAtPort(port).isEmpty()) validateFlatEndpoint(segment, port);
        }
    }

    private static void validateFlatEndpoint(TrackSegment segment, UUID port) {
        boolean start = segment.startNodeId().equals(port);
        if (Math.abs(segment.geometry().sample(start ? 0 : segment.geometry().getLength()).tangent().y()) > 1e-6) {
            throw new IllegalArgumentException("External track must meet a horizontal turnout port");
        }
    }

    /** Internal path nodes cannot become external junctions without splitting the owning structure. */
    public boolean isAssemblyInternalNode(UUID node) {
        return connections(node).stream().map(c -> assemblyForSegment(c.segmentId())).filter(Objects::nonNull)
                .anyMatch(a -> a.ports().values().stream().noneMatch(p -> p.nodeId().equals(node)));
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
    public List<TrackAssemblyInstance> assembliesAtPort(UUID id) {
        return portAssemblies.getOrDefault(id, Set.of()).stream().map(assemblies::get).toList();
    }
    public void addAssemblyListener(Consumer<TrackAssemblyInstance> listener) { assemblyListeners.add(listener); }
    public void removeAssemblyListener(Consumer<TrackAssemblyInstance> listener) { assemblyListeners.remove(listener); }
    public void addAssemblyRemoveListener(Consumer<TrackAssemblyInstance> listener) { assemblyRemoveListeners.add(listener); }
    public void removeAssemblyRemoveListener(Consumer<TrackAssemblyInstance> listener) { assemblyRemoveListeners.remove(listener); }

    public record PreparedAssembly(TrackAssemblyInstance instance, List<TrackNode> nodes, List<TrackSegment> segments) {}

    /** Pure preparation shared by server validation and the atomic placement. Curves point into the draft. */
    public PreparedAssembly prepareAssembly(TrackConstructionType kind, List<TrackPlacementEndpoint> endpoints,
                                             double gauge, List<TrackCurveParameters> curves, List<Boolean> edited,
                                             double yaw, String modelId) {
        if (!kind.implemented() || endpoints.size() != kind.pointCount()) throw new IllegalArgumentException("Incomplete structure");
        var definition = AssemblyPlacementGeometry.definition(kind);
        var geometry = AssemblyPlacementGeometry.create(kind, endpoints.stream().map(TrackPlacementEndpoint::position).toList(),
                gauge, curves, yaw);
        var preparedNodes = new LinkedHashMap<UUID, TrackNode>();
        var ports = new LinkedHashMap<String, TrackPort>();
        var names = List.of("A", "B", "C", "D");
        for (int i = 0; i < endpoints.size(); i++) {
            var endpoint = endpoints.get(i);
            var node = placementNode(endpoint, gauge);
            if (endpoint.nodeId() != null && isAssemblyInternalNode(node.id())) {
                throw new IllegalArgumentException("Cannot connect to an assembly's internal node");
            }
            if (preparedNodes.containsKey(node.id())) throw new IllegalArgumentException("Duplicate structure endpoint");
            var updated = new TrackNode(node.id(), node.position(), gauge,
                    endpoint.nodeId() == null || edited.get(i) ? curves.get(i) : node.curve(), node.verticalCurve());
            preparedNodes.put(node.id(), updated);
            ports.put(names.get(i), new TrackPort(node.id(), geometry.ports().get(names.get(i)).outwardYaw()));
        }
        var batch = new ArrayList<TrackSegment>();
        var paths = new LinkedHashMap<String, TrackPath>();
        for (var entry : geometry.paths().entrySet()) {
            var route = definition.routes().get(entry.getKey());
            var part = entry.getValue().getFirst();
            var curve = part instanceof BezierTrackGeometry bezier ? bezier : null;
            var segment = prepareSegment(UUID.randomUUID(), preparedNodes.get(ports.get(route.entrance()).nodeId()),
                    preparedNodes.get(ports.get(route.exit()).nodeId()), curve == null ? null : curve.control1(),
                    curve == null ? null : curve.control2(), modelId, part);
            batch.add(segment);
            paths.put(entry.getKey(), new TrackPath(List.of(new TrackConnection(segment.id(), true))));
        }
        var instance = new TrackAssemblyInstance(UUID.randomUUID(), definition, ports, paths, endpoints.getFirst().position(),
                geometry.ports().get("A").outwardYaw() + 180, gauge, definition.initialState(), 0, null, geometry, modelId);
        var byId = batch.stream().collect(java.util.stream.Collectors.toMap(TrackSegment::id, segment -> segment));
        validateAssembly(instance, preparedNodes::get, byId::get);
        return new PreparedAssembly(instance, List.copyOf(preparedNodes.values()), List.copyOf(batch));
    }

    public TrackAssemblyInstance createAssembly(TrackConstructionType kind, List<TrackPlacementEndpoint> endpoints,
                                                 double gauge, List<TrackCurveParameters> curves, List<Boolean> edited,
                                                 double yaw, String modelId) {
        return commitAssembly(prepareAssembly(kind, endpoints, gauge, curves, edited, yaw, modelId));
    }

    private TrackAssemblyInstance commitAssembly(PreparedAssembly prepared) {
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
            var external = connections.getOrDefault(port.nodeId(), List.of()).stream()
                    .filter(c -> !value.segments().contains(c.segmentId())).toList();
            for (var connection : external) validateFlatEndpoint(segment(connection.segmentId()), port.nodeId());
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
                if (segment.geometry() instanceof VerticalTrackGeometry) {
                    throw new IllegalArgumentException("Turnout internal paths must remain horizontal");
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
        value.ports().values().forEach(port -> portAssemblies.computeIfAbsent(port.nodeId(), ignored -> new LinkedHashSet<>()).add(value.id()));
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
        var updated = occupant == null ? old.finishTrailingPass() : old.withOccupant(occupant);
        assemblies.put(id, updated);
        onChange.run();
        if (old.revision() != updated.revision()) for (var listener : assemblyListeners) listener.accept(updated);
    }

    /** Validate path identity before acquisition; the reservation manager checks physical admission and exclusivity. */
    public void validateTrailingPass(UUID id, List<UUID> owners, String pathId) {
        var value = assembly(id);
        if (!value.definition().supportsTrailingPass() || !value.paths().containsKey(pathId)
                || value.occupant() != null && !owners.contains(value.occupant())) {
            throw new IllegalArgumentException("Invalid turnout path or protection representative");
        }
    }

    /** Publish the return record and actual throw together, after validating this movement's exclusivity. */
    public void throwTrailingPass(UUID id, String pathId) {
        var old = assembly(id);
        var returnState = old.trailingPass() == null ? old.state() : old.trailingPass().returnState();
        var state = old.state();
        for (var control : old.definition().routes().get(pathId).requirements().entrySet()) {
            state = state.withControl(control.getKey(), control.getValue());
        }
        if (state.equals(old.state())) return;
        // A later trailing move may return to the original position while protection is still held.
        var passage = state.equals(returnState) ? null : new TrailingPass(pathId, returnState);
        var updated = old.withTrailingPass(passage).withState(state);
        assemblies.put(id, updated); onChange.run();
        for (var listener : assemblyListeners) listener.accept(updated);
    }

    public void removeAssembly(UUID id) {
        var value = assembly(id);
        var removed = value.segments().stream().map(this::segment).toList();
        assemblies.remove(id);
        value.ports().values().forEach(port -> {
            var owners = portAssemblies.get(port.nodeId());
            owners.remove(id);
            if (owners.isEmpty()) portAssemblies.remove(port.nodeId());
        });
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
