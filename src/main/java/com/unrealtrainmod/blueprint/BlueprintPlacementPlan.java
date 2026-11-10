/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.blueprint;

import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WireData;
import com.unrealtrainmod.wiring.WiringCatalog;
import com.unrealtrainmod.wiring.WiringScriptPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Pure geometry expansion shared by the editor and authoritative world construction. */
public record BlueprintPlacementPlan(List<Station> stations, double length) {
    public static final int MAX_STATIONS = 4096, MAX_OBJECTS = 32768;
    public record Station(int index, double distance, List<ConnectorData> nodes, List<WireData> wires) {
        public Station { nodes = List.copyOf(nodes); wires = List.copyOf(wires); }
    }
    public BlueprintPlacementPlan { stations = List.copyOf(stations); }
    public int nodeCount() { return stations.stream().mapToInt(s -> s.nodes.size()).sum(); }
    public int wireCount() { return stations.stream().mapToInt(s -> s.wires.size()).sum(); }

    public static BlueprintPlacementPlan generate(BlueprintDefinition definition, List<BlueprintPaths.Path> paths,
                                                   double spacing, boolean startB, UUID identity) {
        definition.validate(true);
        if (paths.size() != definition.tracks().size()) throw new IllegalArgumentException("binding");
        if (!Double.isFinite(spacing) || spacing < .01 || spacing > 256) throw new IllegalArgumentException("spacing");
        double length = paths.getFirst().length();
        if (!Double.isFinite(length) || length <= 1e-6) throw new IllegalArgumentException("path_length");
        int spans = (int)Math.ceil((length - 1e-7) / spacing);
        if (spans < 1 || spans >= MAX_STATIONS) throw new IllegalArgumentException("limit");
        var stations = new ArrayList<Station>(); var mappings = new ArrayList<Map<UUID, ConnectorData>>();
        var intersections = new ArrayList<IntersectionIndex>();
        for (int t = 1; t < paths.size(); t++) intersections.add(new IntersectionIndex(paths.get(t)));
        var trackDistance = new double[paths.size()]; int objects = 0;
        for (int index = 0; index <= spans; index++) {
            double s = Math.min(index * spacing, length); if (index == spans) s = length;
            var basis = paths.getFirst().sample(s); var poses = new ArrayList<TrackPose>(); poses.add(basis);
            for (int t = 1; t < paths.size(); t++) {
                try { trackDistance[t] = intersections.get(t - 1).intersect(basis, trackDistance[t], index == 0); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException(e.getMessage() + "@" + index); }
                poses.add(paths.get(t).sample(trackDistance[t]));
            }
            var section = definition.section(index + (startB ? 1 : 0));
            if (section.nodes().isEmpty()) throw new IllegalArgumentException("empty");
            var nodes = new ArrayList<ConnectorData>(); var map = new HashMap<UUID, ConnectorData>();
            for (var node : section.nodes()) {
                var pose = poses.get(node.track()); var p = pose.position(); var tangent = pose.tangent();
                double yaw = Math.atan2(tangent.x(), tangent.z()); double sin = Math.sin(yaw), cos = Math.cos(yaw);
                var local = node.position();
                var origin = new Vec3(p.x() + cos * local.x + sin * local.z, p.y() + local.y,
                        p.z() - sin * local.x + cos * local.z);
                var transformed = node.at(origin, Math.toDegrees(yaw));
                var world = new ConnectorData(id(identity, "n:" + index + ":" + node.id()), node.model(), origin,
                        node.face(), Vec3.ZERO, transformed.angles(), 0);
                nodes.add(world); map.put(node.id(), world);
            }
            mappings.add(map); stations.add(new Station(index, s, nodes, List.of())); objects += nodes.size();
            if (objects > MAX_OBJECTS) throw new IllegalArgumentException("limit");
        }
        var duplicates = new HashSet<String>();
        for (int index = 0; index < stations.size(); index++) {
            var section = definition.section(index + (startB ? 1 : 0)); var wires = new ArrayList<WireData>();
            // Store incoming spans with their destination station: both endpoints exist before publishing it.
            for (var edge : section.edges()) add(wires, mappings.get(index).get(edge.first()), mappings.get(index).get(edge.second()), edge, identity, duplicates, index);
            if (index > 0) for (var edge : definition.section(index - 1 + (startB ? 1 : 0)).outgoing())
                add(wires, mappings.get(index - 1).get(edge.first()), mappings.get(index).get(edge.second()), edge, identity, duplicates, index);
            var station = stations.get(index); stations.set(index, new Station(index, station.distance, station.nodes, wires));
            objects += wires.size(); if (objects > MAX_OBJECTS) throw new IllegalArgumentException("limit");
        }
        return new BlueprintPlacementPlan(stations, length);
    }
    private static void add(List<WireData> wires, ConnectorData a, ConnectorData b, BlueprintDefinition.Edge edge,
                            UUID identity, HashSet<String> duplicates, int index) {
        var delta = b.wirePoint().subtract(a.wirePoint()); double distance = delta.length();
        if (distance < 1e-6 || distance > 256) throw new IllegalArgumentException("span@" + index);
        if (!WiringScriptPolicy.of(WiringCatalog.find(edge.model()).renderer()).validSpan(delta)) throw new IllegalArgumentException("short_span@" + index);
        var first = a.id().compareTo(b.id()) < 0 ? a.id() : b.id(); var second = first.equals(a.id()) ? b.id() : a.id();
        String key = first + ":" + second + ":" + edge.model();
        if (!duplicates.add(key)) throw new IllegalArgumentException("duplicate@" + index);
        wires.add(new WireData(id(identity, "w:" + key), a.id(), b.id(), edge.model()));
    }
    private static UUID id(UUID identity, String key) { return UUID.nameUUIDFromBytes((identity + ":" + key).getBytes(StandardCharsets.UTF_8)); }
    /** Conservative arc-length bounds skip distant portions instead of rescanning the whole route per station. */
    private static final class IntersectionIndex {
        private record Range(int from, int to, double minX, double maxX, double minZ, double maxZ, Range left, Range right) {}
        private final BlueprintPaths.Path path;
        private final int count;
        private final double step;
        private final double[] x, z;
        private final Range root;
        IntersectionIndex(BlueprintPaths.Path path) {
            this.path = path; count = Math.max(1, (int)Math.ceil(path.length() * 2)); step = path.length() / count;
            x = new double[count + 1]; z = new double[count + 1];
            for (int i = 0; i <= count; i++) { var p = path.sample(i * step).position(); x[i] = p.x(); z[i] = p.z(); }
            root = range(0, count);
        }
        private Range range(int from, int to) {
            Range a = null, b = null; double minX, maxX, minZ, maxZ;
            if (to - from <= 16) {
                minX = maxX = x[from]; minZ = maxZ = z[from];
                for (int i = from + 1; i <= to; i++) { minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]); minZ = Math.min(minZ, z[i]); maxZ = Math.max(maxZ, z[i]); }
                // A point between samples cannot move farther than its 3D arc-length bracket.
                minX -= step; maxX += step; minZ -= step; maxZ += step;
            } else {
                int mid = (from + to) / 2; a = range(from, mid); b = range(mid, to);
                minX = Math.min(a.minX, b.minX); maxX = Math.max(a.maxX, b.maxX); minZ = Math.min(a.minZ, b.minZ); maxZ = Math.max(a.maxZ, b.maxZ);
            }
            return new Range(from, to, minX, maxX, minZ, maxZ, a, b);
        }
        double intersect(TrackPose basis, double previous, boolean first) {
            var roots = new ArrayList<Double>(); double minimum = first ? 0 : previous + 1e-5;
            search(root, basis, minimum, roots);
            if (roots.size() != 1) throw new IllegalArgumentException("intersection");
            var pose = path.sample(roots.getFirst()); var forward = basis.tangent();
            if (pose.tangent().x() * forward.x() + pose.tangent().z() * forward.z() <= 0) throw new IllegalArgumentException("direction");
            return roots.getFirst();
        }
        private void search(Range range, TrackPose basis, double minimum, List<Double> roots) {
            if (range.to * step < minimum || roots.size() > 1) return;
            var origin = basis.position(); var forward = basis.tangent(); double dx = forward.x(), dz = forward.z();
            double low = ((dx >= 0 ? range.minX : range.maxX) - origin.x()) * dx + ((dz >= 0 ? range.minZ : range.maxZ) - origin.z()) * dz;
            double high = ((dx >= 0 ? range.maxX : range.minX) - origin.x()) * dx + ((dz >= 0 ? range.maxZ : range.minZ) - origin.z()) * dz;
            if (low > 1e-6 || high < -1e-6) return;
            if (range.left != null) { search(range.left, basis, minimum, roots); search(range.right, basis, minimum, roots); return; }
            for (int i = range.from; i < range.to; i++) {
                double left = Math.max(minimum, i * step), right = (i + 1) * step; if (left > right) continue;
                double value = plane(path.sample(left), origin.x(), origin.z(), dx, dz);
                double next = (x[i + 1] - origin.x()) * dx + (z[i + 1] - origin.z()) * dz;
                if (Math.abs(value) < 1e-6) appendRoot(roots, left);
                if (value * next < 0) {
                    double a = left, b = right, va = value;
                    for (int n = 0; n < 30; n++) {
                        double mid = (a + b) / 2, v = plane(path.sample(mid), origin.x(), origin.z(), dx, dz);
                        if (va * v <= 0) b = mid; else { a = mid; va = v; }
                    }
                    appendRoot(roots, (a + b) / 2);
                } else if (Math.abs(next) < 1e-6) appendRoot(roots, right);
            }
        }
    }
    private static void appendRoot(List<Double> roots, double root) {
        if (roots.isEmpty() || Math.abs(roots.getLast() - root) > 1e-4) roots.add(root);
    }
    private static double plane(TrackPose pose, double x, double z, double dx, double dz) {
        return (pose.position().x() - x) * dx + (pose.position().z() - z) * dz;
    }
}
