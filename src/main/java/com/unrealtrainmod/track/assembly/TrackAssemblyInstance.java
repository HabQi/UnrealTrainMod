/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGeometryDefinition;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;

/** Immutable structure and controls; occupancy deliberately survives unloads. */
public final class TrackAssemblyInstance {
    private final UUID id, occupant;
    private final TrackAssemblyDefinition definition;
    private final Map<String, TrackPort> ports;
    private final Map<String, TrackPath> paths;
    private final List<UUID> segments;
    private final TrackVector origin;
    private final double yaw, gauge;
    private final JunctionState state;
    private final long revision;
    private final TrackAssemblyDefinition.Geometry geometry;
    private final AABB bounds;
    private final String modelId;
    private final TrailingPass trailingPass;

    public TrackAssemblyInstance(UUID id, TrackAssemblyDefinition definition,
                                 Map<String, TrackPort> ports, Map<String, TrackPath> paths,
                                 TrackVector origin, double yaw, double gauge,
                                 JunctionState state, long revision, UUID occupant,
                                 TrackAssemblyDefinition.Geometry geometry) {
        this(id, definition, ports, paths, origin, yaw, gauge, state, revision, occupant, geometry, null);
    }

    public TrackAssemblyInstance(UUID id, TrackAssemblyDefinition definition,
                                 Map<String, TrackPort> ports, Map<String, TrackPath> paths,
                                 TrackVector origin, double yaw, double gauge,
                                 JunctionState state, long revision, UUID occupant,
                                 TrackAssemblyDefinition.Geometry geometry, String modelId) {
        this.modelId = modelId;
        this.trailingPass = null;
        if (!Double.isFinite(yaw) || !Double.isFinite(gauge) || gauge <= 0
                || !Double.isFinite(origin.x()) || !Double.isFinite(origin.y()) || !Double.isFinite(origin.z())
                || (modelId != null && modelId.isBlank())) throw new IllegalArgumentException("Invalid assembly parameters");
        if (revision < 0) throw new IllegalArgumentException("Invalid assembly revision");
        this.id = Objects.requireNonNull(id);
        this.definition = Objects.requireNonNull(definition);
        this.ports = Collections.unmodifiableMap(new TreeMap<>(ports));
        this.paths = Collections.unmodifiableMap(new TreeMap<>(paths));
        this.origin = origin; this.yaw = ((yaw % 360) + 540) % 360 - 180; this.gauge = gauge;
        definition.validateState(state);
        this.state = state; this.revision = revision; this.occupant = occupant;
        this.geometry = geometry;
        if (!ports.keySet().equals(Set.of("A", "B", "C")) || !ports.keySet().equals(geometry.ports().keySet())
                || !paths.keySet().equals(definition.routes().keySet()) || !paths.keySet().equals(geometry.paths().keySet())) {
            throw new IllegalArgumentException("Assembly ports or paths do not match definition");
        }
        var nodeIds = new HashSet<UUID>();
        for (var entry : this.ports.entrySet()) {
            var port = entry.getValue();
            double expected = geometry.ports().get(entry.getKey()).outwardYaw();
            double difference = ((port.outwardYaw() - expected) % 360 + 540) % 360 - 180;
            if (!nodeIds.add(port.nodeId()) || Math.abs(difference) > 1e-6) {
                throw new IllegalArgumentException("Invalid assembly port");
            }
        }
        segments = this.paths.values().stream().flatMap(path -> path.steps().stream())
                .map(TrackConnection::segmentId).distinct().toList();
        if (segments.stream().anyMatch(nodeIds::contains)) throw new IllegalArgumentException("Assembly IDs overlap");
        for (var entry : this.paths.entrySet()) {
            if (entry.getValue().steps().size() != geometry.paths().get(entry.getKey()).size()) {
                throw new IllegalArgumentException("Assembly path does not match definition geometry");
            }
        }
        if (definition == TrackAssemblyDefinition.CUSTOM) {
            var a = geometry.ports().get("A").position();
            for (var parts : geometry.paths().values()) {
                if (parts.size() != 1 || !(parts.getFirst() instanceof BezierTrackGeometry)) {
                    throw new IllegalArgumentException("Custom turnout requires two single Bezier paths");
                }
                var part = parts.getFirst();
                var t = part.sample(0).tangent();
                double angle = Math.toRadians(geometry.ports().get("A").outwardYaw());
                if (part.getLength() > TrackPlacementGeometry.MAX_LENGTH
                        || Math.abs(part.sample(0).position().y() - a.y()) > 1e-6
                        || Math.abs(part.sample(part.getLength()).position().y() - a.y()) > 1e-6
                        || Math.sin(angle) * t.x() - Math.cos(angle) * t.z() < 1 - 1e-6) {
                    throw new IllegalArgumentException("Custom turnout paths must share a flat entrance tangent");
                }
            }
        }
        bounds = createBounds();
    }

    private TrackAssemblyInstance(TrackAssemblyInstance source, JunctionState state, long revision, UUID occupant, TrailingPass trailingPass) {
        id = source.id; definition = source.definition; ports = source.ports; paths = source.paths; segments = source.segments;
        origin = source.origin; yaw = source.yaw; gauge = source.gauge;
        this.state = state; this.revision = revision; this.occupant = occupant;
        geometry = source.geometry; bounds = source.bounds; modelId = source.modelId; this.trailingPass = trailingPass;
    }

    public TrailingPass trailingPass() { return trailingPass; }
    public String modelId() { return modelId; }
    public UUID id() { return id; }
    public UUID occupant() { return occupant; }
    public TrackAssemblyDefinition definition() { return definition; }
    public TrackVector origin() { return origin; }
    public double yaw() { return yaw; }
    public double gauge() { return gauge; }
    public JunctionState state() { return state; }
    public long revision() { return revision; }
    public Map<String, TrackPort> ports() { return ports; }
    public Map<String, TrackPath> paths() { return paths; }
    public List<UUID> segments() { return segments; }
    public TrackAssemblyDefinition.Geometry geometry() { return geometry; }

    public TrackPort portAt(UUID nodeId) {
        return ports.values().stream().filter(port -> port.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    public TrackAssemblyInstance withState(JunctionState value) {
        return withState(value, Math.incrementExact(revision));
    }
    public TrackAssemblyInstance withState(JunctionState value, long version) {
        if (version < 0) throw new IllegalArgumentException("Invalid assembly state revision");
        definition.validateState(value);
        return new TrackAssemblyInstance(this, value, version, occupant, trailingPass);
    }
    public TrackAssemblyInstance withOccupant(UUID value) {
        return new TrackAssemblyInstance(this, state, revision, value, trailingPass);
    }

    public TrackAssemblyInstance withTrailingPass(TrailingPass passage) {
        if (passage != null) {
            if (occupant == null || !paths.containsKey(passage.pathId())) throw new IllegalArgumentException("Invalid trailing passage owner/path");
            definition.validateState(passage.returnState());
            if (definition.routes().get(passage.pathId()).enabled(passage.returnState())) {
                throw new IllegalArgumentException("Trailing passage must originate on a closed path");
            }
        }
        return new TrackAssemblyInstance(this, state, revision, occupant, passage);
    }

    public TrackAssemblyInstance finishTrailingPass() {
        var restored = trailingPass == null ? state : trailingPass.returnState();
        return new TrackAssemblyInstance(this, restored, state.equals(restored) ? revision : Math.incrementExact(revision), null, null);
    }

    /** Conservative protection covers all paths and vehicle overhang. */
    public AABB bounds() { return bounds; }

    private AABB createBounds() {
        double minX = origin.x(), maxX = origin.x(), minZ = origin.z(), maxZ = origin.z();
        for (var port : geometry.ports().values()) {
            minX = Math.min(minX, port.position().x()); maxX = Math.max(maxX, port.position().x());
            minZ = Math.min(minZ, port.position().z()); maxZ = Math.max(maxZ, port.position().z());
        }
        for (var parts : geometry.paths().values()) for (var part : parts) {
            for (var control : TrackGeometryDefinition.from(part).controls()) {
                minX = Math.min(minX, control.x()); maxX = Math.max(maxX, control.x());
                minZ = Math.min(minZ, control.z()); maxZ = Math.max(maxZ, control.z());
            }
        }
        double padding = Math.max(2, (gauge + 0.9) / 2);
        return new AABB(minX - padding, origin.y() - 1, minZ - padding,
                maxX + padding, origin.y() + 4, maxZ + padding);
    }

    public boolean coversChunk(int x, int z) {
        return bounds.maxX >= (double)x * 16 && bounds.minX < (double)x * 16 + 16
                && bounds.maxZ >= (double)z * 16 && bounds.minZ < (double)z * 16 + 16;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("Id", id); tag.putString("Definition", definition.name());
        var savedPorts = new ListTag();
        ports.forEach((name, port) -> {
            var entry = new CompoundTag();
            entry.putString("Name", name); entry.putUUID("Node", port.nodeId()); entry.putDouble("Yaw", port.outwardYaw());
            savedPorts.add(entry);
        });
        tag.put("Ports", savedPorts);
        if (modelId != null) tag.putString("ModelId", modelId);
        var savedGeometry = new CompoundTag();
        geometry.ports().forEach((name, port) -> savedGeometry.put(name, vectorTag(port.position())));
        geometry.paths().forEach((name, parts) -> {
            var list = new ListTag();
            for (var part : parts) {
                var item = new CompoundTag();
                item.put("Start", vectorTag(part.sample(0).position()));
                item.put("End", vectorTag(part.sample(part.getLength()).position()));
                var controls = new ListTag();
                for (var point : TrackGeometryDefinition.from(part).controls()) {
                    var control = new CompoundTag(); control.putDouble("X", point.x()); control.putDouble("Z", point.z());
                    controls.add(control);
                }
                item.put("Controls", controls); list.add(item);
            }
            savedGeometry.put(name, list);
        });
        tag.put("Geometry", savedGeometry);
        var savedPaths = new ListTag();
        paths.forEach((name, path) -> {
            var entry = new CompoundTag();
            entry.putString("Name", name);
            var steps = new ListTag();
            for (var step : path.steps()) {
                var item = new CompoundTag();
                item.putUUID("Segment", step.segmentId()); item.putBoolean("FromStart", step.fromStart()); steps.add(item);
            }
            entry.put("Steps", steps); savedPaths.add(entry);
        });
        tag.put("Paths", savedPaths);
        tag.putDouble("X", origin.x()); tag.putDouble("Y", origin.y()); tag.putDouble("Z", origin.z());
        tag.putDouble("Yaw", yaw); tag.putDouble("Gauge", gauge);
        tag.put("Controls", state.save()); tag.putLong("Revision", revision);
        if (occupant != null) tag.putUUID("Occupant", occupant);
        if (trailingPass != null) {
            var passage = new CompoundTag(); passage.putString("Path", trailingPass.pathId());
            passage.put("ReturnState", trailingPass.returnState().save()); tag.put("TrailingPass", passage);
        }
        return tag;
    }

    /** Geometry and actual control state only; private ownership and return state stay on the server. */
    public CompoundTag saveForClient() {
        var tag = save(); tag.remove("Occupant"); tag.remove("TrailingPass"); return tag;
    }

    public static TrackAssemblyInstance load(CompoundTag tag) {
        validateHeader(tag);
        var ports = new LinkedHashMap<String, TrackPort>();
        var savedPorts = readList(tag, "Ports");
        for (int i = 0; i < savedPorts.size(); i++) {
            var entry = savedPorts.getCompound(i);
            String name = entry.getString("Name");
            if (name.isBlank() || !entry.contains("Yaw", Tag.TAG_DOUBLE)
                    || ports.putIfAbsent(name, new TrackPort(readUuid(entry, "Node"), entry.getDouble("Yaw"))) != null) {
                throw new IllegalArgumentException("Invalid or duplicate assembly port");
            }
        }
        var paths = new LinkedHashMap<String, TrackPath>();
        var savedPaths = readList(tag, "Paths");
        for (int i = 0; i < savedPaths.size(); i++) {
            var entry = savedPaths.getCompound(i);
            var steps = new ArrayList<TrackConnection>();
            var savedSteps = readList(entry, "Steps");
            for (int j = 0; j < savedSteps.size(); j++) {
                var step = savedSteps.getCompound(j);
                if (!step.contains("FromStart", Tag.TAG_BYTE)
                        || (step.getByte("FromStart") != 0 && step.getByte("FromStart") != 1)) {
                    throw new IllegalArgumentException("Invalid assembly path direction");
                }
                steps.add(new TrackConnection(readUuid(step, "Segment"), step.getBoolean("FromStart")));
            }
            String name = entry.getString("Name");
            if (name.isBlank() || paths.putIfAbsent(name, new TrackPath(steps)) != null) {
                throw new IllegalArgumentException("Invalid or duplicate assembly path");
            }
        }
        if (!tag.contains("Controls", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing assembly controls");
        return create(tag, ports, paths, JunctionState.load(tag.getCompound("Controls")));
    }

    /** Version 5 conversion is used only by world storage, never by network decoding. */
    public static TrackAssemblyInstance loadLegacy(CompoundTag tag) {
        validateHeader(tag);
        var definition = TrackAssemblyDefinition.valueOf(tag.getString("Definition"));
        double yaw = tag.getDouble("Yaw");
        var ports = Map.of("A", new TrackPort(readUuid(tag, "A"), yaw + 180),
                "B", new TrackPort(readUuid(tag, "B"), yaw), "C", new TrackPort(readUuid(tag, "C"), definition.exitYaw(yaw)));
        var paths = Map.of("straight", new TrackPath(List.of(new TrackConnection(readUuid(tag, "Straight"), true))),
                "diverging", new TrackPath(List.of(new TrackConnection(readUuid(tag, "Diverging"), true))));
        var position = switch (tag.getString("State")) {
            case "STRAIGHT" -> SwitchPosition.NORMAL;
            case "DIVERGING" -> SwitchPosition.REVERSE;
            default -> throw new IllegalArgumentException("Invalid legacy turnout state");
        };
        return create(tag, ports, paths, new JunctionState(Map.of("main", position)));
    }

    private static TrackAssemblyInstance create(CompoundTag tag, Map<String, TrackPort> ports,
                                                 Map<String, TrackPath> paths, JunctionState state) {
        if (tag.contains("ModelId") && !tag.contains("ModelId", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Invalid turnout model ID");
        }
        var definition = TrackAssemblyDefinition.valueOf(tag.getString("Definition"));
        var origin = new TrackVector(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
        double yaw = ((tag.getDouble("Yaw") % 360) + 540) % 360 - 180;
        double gauge = tag.getDouble("Gauge");
        var value = new TrackAssemblyInstance(readUuid(tag, "Id"), definition, ports, paths, origin, yaw, gauge,
                state, tag.getLong("Revision"), tag.hasUUID("Occupant") ? tag.getUUID("Occupant") : null,
                tag.contains("Geometry", Tag.TAG_COMPOUND) ? readGeometry(tag.getCompound("Geometry"), ports, paths, gauge)
                        : definition.geometry(origin, yaw, gauge),
                tag.contains("ModelId", Tag.TAG_STRING) ? net.minecraft.resources.ResourceLocation.parse(tag.getString("ModelId")).toString() : null);
        if (tag.contains("TrailingPass")) {
            if (!tag.contains("TrailingPass", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid trailing passage");
            var passage = tag.getCompound("TrailingPass");
            if (!passage.contains("Path", Tag.TAG_STRING) || !passage.contains("ReturnState", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Incomplete trailing passage");
            }
            value = value.withTrailingPass(new TrailingPass(passage.getString("Path"), JunctionState.load(passage.getCompound("ReturnState"))));
        }
        return value;
    }

    private static CompoundTag vectorTag(TrackVector p) {
        var tag = new CompoundTag(); tag.putDouble("X", p.x()); tag.putDouble("Y", p.y()); tag.putDouble("Z", p.z());
        return tag;
    }

    private static TrackVector readVector(CompoundTag tag) {
        for (String key : List.of("X", "Y", "Z")) {
            if (!tag.contains(key, Tag.TAG_DOUBLE) || !Double.isFinite(tag.getDouble(key))) {
                throw new IllegalArgumentException("Invalid turnout position");
            }
        }
        return new TrackVector(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
    }

    private static TrackAssemblyDefinition.Geometry readGeometry(CompoundTag tag, Map<String, TrackPort> ports,
                                                                 Map<String, TrackPath> paths, double gauge) {
        var portGeometry = new LinkedHashMap<String, TrackAssemblyDefinition.PortGeometry>();
        ports.forEach((name, port) -> portGeometry.put(name,
                new TrackAssemblyDefinition.PortGeometry(readVector(tag.getCompound(name)), port.outwardYaw())));
        var pathGeometry = new LinkedHashMap<String, List<TrackGeometry>>();
        paths.forEach((name, path) -> {
            var parts = new ArrayList<TrackGeometry>();
            var list = readList(tag, name);
            for (int i = 0; i < list.size(); i++) {
                var item = list.getCompound(i);
                var controls = new ArrayList<TrackPoint>();
                var savedControls = readList(item, "Controls");
                for (int j = 0; j < savedControls.size(); j++) {
                    var control = savedControls.getCompound(j);
                    if (!control.contains("X", Tag.TAG_DOUBLE) || !control.contains("Z", Tag.TAG_DOUBLE)) {
                        throw new IllegalArgumentException("Missing turnout control point");
                    }
                    controls.add(new TrackPoint(control.getDouble("X"), control.getDouble("Z")));
                }
                var start = readVector(item.getCompound("Start"));
                var end = readVector(item.getCompound("End"));
                if (Math.abs(start.y() - end.y()) > 1e-6) throw new IllegalArgumentException("Turnout must be horizontal");
                parts.add(new TrackGeometryDefinition(controls, List.of()).create(start, end, gauge));
            }
            pathGeometry.put(name, parts);
        });
        return new TrackAssemblyDefinition.Geometry(portGeometry, pathGeometry);
    }

    private static void validateHeader(CompoundTag tag) {
        readUuid(tag, "Id");
        if (!tag.contains("Definition", Tag.TAG_STRING) || !tag.contains("Revision", Tag.TAG_LONG)
                || (tag.contains("Occupant") && !tag.hasUUID("Occupant"))) {
            throw new IllegalArgumentException("Invalid assembly header");
        }
        for (String key : List.of("X", "Y", "Z", "Yaw", "Gauge")) {
            if (!tag.contains(key, Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Invalid assembly number: " + key);
        }
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) throw new IllegalArgumentException("Missing assembly UUID: " + key);
        return tag.getUUID(key);
    }

    private static ListTag readList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing assembly list: " + key);
        var list = (ListTag)tag.get(key);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Invalid assembly list: " + key);
        return list;
    }
}
