/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.blueprint;

import com.mojang.serialization.Codec;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WiringCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Item-owned recipe. Node UUIDs are local to a section, never world entity identities. */
public record BlueprintDefinition(String name, boolean alternating, List<Reference> tracks, Section a, Section b) {
    public static final int VERSION = 1, MAX_TRACKS = 16, MAX_NODES = 256, MAX_EDGES = 512;
    public static final Codec<BlueprintDefinition> CODEC = CompoundTag.CODEC.comapFlatMap(tag -> {
        try { return com.mojang.serialization.DataResult.success(load(tag)); }
        catch (RuntimeException e) { return com.mojang.serialization.DataResult.error(() -> "Invalid blueprint: " + e.getMessage()); }
    }, BlueprintDefinition::save);
    public record Reference(String name, double x, double y, double gauge) {}
    public record Node(UUID id, ResourceLocation model, int track, Vec3 position, Direction face, Vec3 angles) {
        public ConnectorData at(Vec3 origin, double yaw) {
            // World yaw composes before the authored installation transform.
            return new ConnectorData(id, model, origin, face, Vec3.ZERO, angles.add(0, yaw, 0), 0);
        }
    }
    public record Edge(UUID first, UUID second, ResourceLocation model) {}
    public record Section(List<Node> nodes, List<Edge> edges, List<Edge> outgoing) {
        public Section { nodes = List.copyOf(nodes); edges = List.copyOf(edges); outgoing = List.copyOf(outgoing); }
        public static Section empty() { return new Section(List.of(), List.of(), List.of()); }
    }
    public BlueprintDefinition { tracks = List.copyOf(tracks); }
    public static BlueprintDefinition empty() {
        return new BlueprintDefinition("", false, List.of(new Reference("1", 0, 0, 1.435)), Section.empty(), Section.empty());
    }
    public Section section(int index) { return alternating && (index & 1) != 0 ? b : a; }

    public void validate(boolean resources) {
        if (name.length() > 80 || tracks.isEmpty() || tracks.size() > MAX_TRACKS) throw new IllegalArgumentException("definition");
        for (var track : tracks) {
            if (track.name.length() > 40 || !coordinate(track.x) || !coordinate(track.y)
                    || !Double.isFinite(track.gauge) || track.gauge <= 0 || track.gauge > 16) throw new IllegalArgumentException("definition");
        }
        validate(a, alternating ? b : a, resources);
        // Keep the inactive B section valid too, so switching modes cannot expose unchecked data.
        validate(b, a, resources && alternating);
    }
    private void validate(Section section, Section next, boolean resources) {
        if (section.nodes.size() > MAX_NODES || section.edges.size() + section.outgoing.size() > MAX_EDGES) throw new IllegalArgumentException("limit");
        var ids = new HashSet<UUID>();
        for (var node : section.nodes) {
            if (!ids.add(node.id) || node.track < 0 || node.track >= tracks.size() || !vector(node.position)
                    || !vector(node.angles)) throw new IllegalArgumentException("definition");
            if (resources) {
                var model = WiringCatalog.find(node.model);
                if (model == null || model.wire()) throw new IllegalArgumentException("missing_model");
            }
        }
        var nextIds = new HashSet<UUID>(); next.nodes.forEach(n -> nextIds.add(n.id));
        var duplicates = new HashSet<String>();
        for (boolean outgoing : new boolean[]{false, true}) for (var edge : outgoing ? section.outgoing : section.edges) {
            if (!ids.contains(edge.first) || !(outgoing ? nextIds : ids).contains(edge.second)
                    || !outgoing && edge.first.equals(edge.second)) throw new IllegalArgumentException("endpoint");
            UUID first = edge.first.compareTo(edge.second) < 0 ? edge.first : edge.second;
            UUID second = first.equals(edge.first) ? edge.second : edge.first;
            if (!duplicates.add(outgoing + ":" + (outgoing ? edge.first : first) + ":" + (outgoing ? edge.second : second) + ":" + edge.model))
                throw new IllegalArgumentException("duplicate");
            if (resources) {
                var model = WiringCatalog.find(edge.model);
                if (model == null || !model.wire()) throw new IllegalArgumentException("missing_model");
            }
        }
    }
    private static boolean coordinate(double v) { return Double.isFinite(v) && Math.abs(v) <= 1024; }
    private static boolean vector(Vec3 v) { return coordinate(v.x) && coordinate(v.y) && coordinate(v.z); }

    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Version", VERSION); tag.putString("Name", name); tag.putBoolean("Alternating", alternating);
        var refs = new ListTag();
        for (var r : tracks) { var t = new CompoundTag(); t.putString("Name", r.name); t.putDouble("X", r.x); t.putDouble("Y", r.y); t.putDouble("Gauge", r.gauge); refs.add(t); }
        tag.put("Tracks", refs); tag.put("A", save(a)); tag.put("B", save(b)); return tag;
    }
    public static BlueprintDefinition load(CompoundTag tag) {
        if (tag.getInt("Version") != VERSION) throw new IllegalArgumentException("version");
        var refs = tag.getList("Tracks", 10);
        if (refs.size() > MAX_TRACKS) throw new IllegalArgumentException("limit");
        var tracks = refs.stream().map(t -> { var r = (CompoundTag)t;
            return new Reference(r.getString("Name"), r.getDouble("X"), r.getDouble("Y"), r.getDouble("Gauge")); }).toList();
        var result = new BlueprintDefinition(tag.getString("Name"), tag.getBoolean("Alternating"), tracks,
                loadSection(tag.getCompound("A")), loadSection(tag.getCompound("B")));
        result.validate(false); return result;
    }
    private static CompoundTag save(Section section) {
        var tag = new CompoundTag(); var nodes = new ListTag();
        for (var n : section.nodes) {
            var t = new CompoundTag(); t.putUUID("Id", n.id); t.putString("Model", n.model.toString()); t.putInt("Track", n.track);
            t.putString("Face", n.face.getName()); ConnectorData.putVector(t, "Position", n.position); ConnectorData.putVector(t, "Angles", n.angles); nodes.add(t);
        }
        tag.put("Nodes", nodes); tag.put("Edges", saveEdges(section.edges)); tag.put("Outgoing", saveEdges(section.outgoing)); return tag;
    }
    private static ListTag saveEdges(List<Edge> edges) {
        var list = new ListTag();
        for (var e : edges) { var t = new CompoundTag(); t.putUUID("First", e.first); t.putUUID("Second", e.second); t.putString("Model", e.model.toString()); list.add(t); }
        return list;
    }
    private static Section loadSection(CompoundTag tag) {
        var nodes = tag.getList("Nodes", 10);
        if (nodes.size() > MAX_NODES) throw new IllegalArgumentException("limit");
        return new Section(nodes.stream().map(t -> {
            var n = (CompoundTag)t; var face = Direction.byName(n.getString("Face"));
            if (face == null) throw new IllegalArgumentException("definition");
            return new Node(n.getUUID("Id"), ResourceLocation.parse(n.getString("Model")), n.getInt("Track"),
                    ConnectorData.vector(n, "Position"), face, ConnectorData.vector(n, "Angles"));
        }).toList(), loadEdges(tag.getList("Edges", 10)), loadEdges(tag.getList("Outgoing", 10)));
    }
    private static List<Edge> loadEdges(ListTag list) {
        if (list.size() > MAX_EDGES) throw new IllegalArgumentException("limit");
        return list.stream().map(t -> { var e = (CompoundTag)t;
            return new Edge(e.getUUID("First"), e.getUUID("Second"), ResourceLocation.parse(e.getString("Model"))); }).toList();
    }
}
