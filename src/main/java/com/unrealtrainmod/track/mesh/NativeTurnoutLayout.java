/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Whole-assembly, anchor-local geometry. Contains no script views, instance IDs or control progress. */
public record NativeTurnoutLayout(List<Span> fixed, Map<String, List<Span>> leaves,
                                  List<Sleeper> sleepers, Map<String, TrackPose> actuators) {
    public record Span(TrackPose start, TrackPose end, double lateral, double halfWidth,
                       double startWeight, double endWeight, boolean reverseLeaf, int side) {}
    public record Sleeper(TrackPose pose, double width) {}
    private record End(String control, TrackGeometry main, TrackGeometry branch, boolean mainReverse,
                       boolean branchReverse, String mainPath, String branchPath) {}

    public NativeTurnoutLayout {
        fixed = List.copyOf(fixed);
        var copy = new LinkedHashMap<String, List<Span>>();
        leaves.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        leaves = java.util.Collections.unmodifiableMap(copy);
        sleepers = List.copyOf(sleepers);
        actuators = Map.copyOf(actuators);
    }

    public static NativeTurnoutLayout create(TrackAssemblyInstance assembly) {
        var paths = assembly.geometry().paths();
        var ends = new ArrayList<End>();
        if (assembly.definition() == TrackAssemblyDefinition.CUSTOM) {
            ends.add(new End("main", paths.get("straight").getFirst(), paths.get("diverging").getFirst(),
                    false, false, "straight", "diverging"));
        } else if (assembly.definition() == TrackAssemblyDefinition.SINGLE_CROSSOVER) {
            var crossover = paths.get("crossover").getFirst();
            ends.add(new End("switch_one", paths.get("first_straight").getFirst(), crossover,
                    false, false, "first_straight", "crossover"));
            ends.add(new End("switch_two", paths.get("second_straight").getFirst().reversed(), crossover.reversed(),
                    true, true, "second_straight", "crossover"));
        }
        var fixed = new ArrayList<Span>();
        var leaves = new LinkedHashMap<String, List<Span>>();
        var actuators = new LinkedHashMap<String, TrackPose>();
        var origin = assembly.origin();
        double lateral = (assembly.gauge() + TrackMeshBuilder.RAIL_WIDTH) / 2;
        // One ownership pass over all paths; endpoint regions are not separate turnout structures.
        for (var path : paths.entrySet()) for (var geometry : path.getValue()) {
            double length = geometry.getLength();
            var cuts = new java.util.TreeSet<Double>();
            int count = Math.max(1, (int)Math.ceil(length / 0.5));
            for (int i = 0; i <= count; i++) cuts.add(length * i / count);
            for (var end : ends) {
                if (path.getKey().equals(end.mainPath)) cuts.add(end.mainReverse ? length - tongueLength(end) : tongueLength(end));
                if (path.getKey().equals(end.branchPath)) cuts.add(end.branchReverse ? length - tongueLength(end) : tongueLength(end));
            }
            var samples = cuts.toArray(Double[]::new);
            for (int i = 0; i + 1 < samples.length; i++) {
                double a = samples[i], b = samples[i + 1], middle = (a + b) / 2;
                for (int side : new int[]{-1, 1}) {
                    boolean moving = false;
                    for (var end : ends) {
                        boolean branch = path.getKey().equals(end.branchPath);
                        if (!branch && !path.getKey().equals(end.mainPath)) continue;
                        boolean reversed = branch ? end.branchReverse : end.mainReverse;
                        double tongue = tongueLength(end);
                        double distance = reversed ? length - middle : middle;
                        int inner = branch ? -branchSide(end) : branchSide(end);
                        if (reversed) inner = -inner;
                        if (distance >= tongue || side != inner) continue;
                        double wa = Math.max(0, 1 - (reversed ? length - a : a) / tongue);
                        double wb = Math.max(0, 1 - (reversed ? length - b : b) / tongue);
                        leaves.computeIfAbsent(end.control, key -> new ArrayList<>()).add(new Span(
                                local(geometry.sample(a), origin), local(geometry.sample(b), origin), side * lateral,
                                TrackMeshBuilder.RAIL_WIDTH * 0.3, wa, wb, branch, side));
                        moving = true;
                    }
                    if (!moving) fixed.add(new Span(local(geometry.sample(a), origin), local(geometry.sample(b), origin),
                            side * lateral, TrackMeshBuilder.RAIL_WIDTH / 2, 0, 0, false, side));
                }
            }
        }
        for (var end : ends) actuators.put(end.control, local(end.main.sample(Math.min(0.6, tongueLength(end) / 2)), origin));
        var sleepers = new ArrayList<Sleeper>();
        var placed = new HashSet<String>();
        for (var parts : paths.values()) for (var geometry : parts) {
            for (double distance = 0.3; distance < geometry.getLength(); distance += 0.6) {
                var pose = local(geometry.sample(distance), origin);
                String key = Math.round(pose.position().x() * 10000) + ":" + Math.round(pose.position().z() * 10000);
                if (placed.add(key)) sleepers.add(new Sleeper(pose, assembly.gauge() + 0.9));
            }
        }
        return new NativeTurnoutLayout(fixed, leaves, sleepers, actuators);
    }

    private static double tongueLength(End end) { return Math.min(end.main.getLength(), end.branch.getLength()) * 0.35; }

    private static int branchSide(End end) {
        var main = end.main.sample(tongueLength(end));
        var branch = end.branch.sample(tongueLength(end)).position();
        return (branch.x() - main.position().x()) * main.lateral().x()
                + (branch.z() - main.position().z()) * main.lateral().z() >= 0 ? 1 : -1;
    }

    private static TrackPose local(TrackPose pose, TrackVector origin) {
        var p = pose.position();
        return new TrackPose(new TrackVector(p.x() - origin.x(), p.y() - origin.y(), p.z() - origin.z()),
                pose.tangent(), pose.lateral(), pose.normal(), pose.curvature(), pose.gauge());
    }
}
