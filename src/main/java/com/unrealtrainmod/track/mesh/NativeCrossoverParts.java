/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeTurnoutConnections.Policy;
import java.util.*;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.*;

/** Fixed source hardware and the ordinary turnout's four-phase ballast sequence. */
final class NativeCrossoverParts {
    private NativeCrossoverParts() {}
    record Solution(List<NativeDiamondLayout.Part> parts, List<NativeTurnoutLayout.Part> rails,
                    Map<String,NativeTurnoutLayout> switches, List<NativeCrossoverLayout.SleeperZone> zones, List<NativeCrossoverGeometry.Rail> connections) {}
    private record Section(NativeDiamondLayout.RailEnd start, NativeDiamondLayout.RailEnd end) {}
    static Solution build(NativeCrossoverLayout.Kind kind, TrackAssemblyDefinition.Geometry geometry,
            List<NativeCrossoverLayout.Path> paths, TrackVector origin, double gauge, boolean complete) {
        return build(kind, geometry, paths, origin, gauge, complete, complete ? Policy.CROSSOVER_CORE : Policy.REFERENCE);
    }

    private static Solution build(NativeCrossoverLayout.Kind kind, TrackAssemblyDefinition.Geometry geometry,
            List<NativeCrossoverLayout.Path> paths, TrackVector origin, double gauge, boolean complete, Policy policy) {
        double scale = gauge/1.435; var parts = new ArrayList<NativeDiamondLayout.Part>();
        var rails = new ArrayList<NativeTurnoutLayout.Part>(); var switches = new LinkedHashMap<String,NativeTurnoutLayout>();
        var hardwareOwners = new IdentityHashMap<NativeDiamondLayout.Part,Integer>();
        var zones = new ArrayList<NativeCrossoverLayout.SleeperZone>(); var connections = new ArrayList<NativeCrossoverGeometry.Rail>();
        var sections = new ArrayList<Map<Integer,List<Section>>>();
        for (int i = 0; i < paths.size(); i++) sections.add(Map.of(-1,new ArrayList<>(),1,new ArrayList<>()));
        var curves = kind.paths.stream().map(name -> geometry.paths().get(name).getFirst()).toList();
        for (int control = 0; control < kind.cores.size(); control++) {
            var spec = kind.cores.get(control);
            int[] owners = {spec.main(),spec.branch()}; boolean reversed = spec.reversed();
            TrackGeometry main = curves.get(owners[0]), branch = curves.get(owners[1]);
            if (reversed) { main = main.reversed(); branch = branch.reversed(); }
            try (var stage = NativeLayoutProfile.stage(kind.label()+"/core/"+kind.controls.get(control))) {
                if (stage != null) { stage.value("mainPath", paths.get(owners[0]).name()); stage.value("branchPath", paths.get(owners[1]).name()); stage.value("reversed", reversed); }
                int firstPart = parts.size();
                var core = NativeTurnoutLayout.core(main, branch, gauge, policy);
                var moving = new ArrayList<NativeTurnoutLayout.Part>();
                var mainZone = core.blades().getFirst();
                var frogFrame = main.sample(mainZone.frog());
                var frogLocal = add(frogFrame.position(),origin,-1);
                String mainGuard = mainZone.inner() > 0 ? "guardR_short" : "guardL_short";
                double mainGuardEnd = mainZone.frog();
                for (var part : core.layout().parts()) {
                    if (part.moving()) moving.add(part);
                    else if (part.name().equals("railL") || part.name().equals("railR"))
                        rails.add(new NativeTurnoutLayout.Part(part.name(),add(part.position(),origin,-1),part.yaw(),part.pitch(),
                                part.localX(),part.localZ(),part.visibleLength(),part.bladeLength(),part.bladeContact(),part.railBend(),null,null));
                    else if (part.name().startsWith("nose") || part.name().startsWith("wing")) {
                        var position = add(part.position(),origin,-1);
                        if (part.name().equals("wingLf") || part.name().equals("wingRf")) {
                            String side = part.name().substring(4,5);
                            parts.add(new NativeDiamondLayout.Part("wingFront"+side+"_short",position,part.yaw(),part.localX(),part.localZ(),0));
                            var guard = new NativeDiamondLayout.Part("guard"+side+"_short",lowerGuard(position),part.yaw(),part.localX(),part.localZ(),0);
                            parts.add(guard);
                            if (kind == NativeCrossoverLayout.Kind.SCISSORS && guard.name().equals(mainGuard)) {
                                for (var point : footprint(guard,scale))
                                    mainGuardEnd = Math.max(mainGuardEnd, mainZone.frog()+dot(add(point,frogLocal,-1),frogFrame.tangent()));
                            }
                        } else parts.add(new NativeDiamondLayout.Part(part.name(),position,part.yaw(),part.localX(),part.localZ(),0));
                    }
                }
                for (int i = firstPart; i < parts.size(); i++) hardwareOwners.put(parts.get(i),control);
                switches.put(kind.controls.get(control),new NativeTurnoutLayout(origin,scale,moving,0));
                for (var zone : core.blades()) zones.add(new NativeCrossoverLayout.SleeperZone(owners[zone.branch() ? 1 : 0],
                        owners[0],reversed,zone.inner(),zone.tip(),zone.heel(),zone.frog(),mainGuardEnd));
                for (int which = 0; which < 2; which++) for (var exit : which == 0 ? core.main() : core.branch()) {
                    int owner = owners[which], side = reversed ? -exit.side() : exit.side();
                    double length = paths.get(owner).length(), distance = reversed ? length-exit.distance() : exit.distance();
                    var end = new NativeDiamondLayout.RailEnd(side,distance,exit.point(),exit.yaw()+(reversed ? Math.PI : 0),0);
                    double boundary = reversed ? length : 0; var pose = curves.get(owner).sample(boundary);
                    var port = new NativeDiamondLayout.RailEnd(side,boundary,add(pose.position(),pose.lateral(),side*NativeCrossoverLayout.RAIL*scale),
                            Math.atan2(pose.tangent().x(),pose.tangent().z()),0);
                    boolean stock = exit.side() != core.blades().get(which).inner();
                    sections.get(owner).get(side).add(stock ? new Section(port,port)
                            : reversed ? new Section(end,port) : new Section(port,end));
                }
            } catch (NativeTurnoutLayout.InvalidLayout failure) {
                throw new NativeCrossoverLayout.InvalidLayout(failure.problem() == NativeTurnoutLayout.Problem.ANGLE
                        ? NativeCrossoverLayout.Problem.ANGLE : NativeCrossoverLayout.Problem.SPACE,
                        failure.diagnostic().in(kind.controls.get(control),
                                paths.get(owners[0]).name()+"|"+paths.get(owners[1]).name()+"/"+failure.diagnostic().path()));
            }
        }
        if (kind == NativeCrossoverLayout.Kind.SCISSORS) {
            try (var stage = NativeLayoutProfile.stage("scissors/centralDiamond")) {
                var core = NativeDiamondLayout.core(curves.get(2),curves.get(3),gauge);
                for (var part : core.layout().parts()) if (!part.name().startsWith("sleeper")) {
                    var position = add(part.position(),origin,-1);
                    parts.add(new NativeDiamondLayout.Part(part.name(),position,part.yaw(),
                            part.localX(),part.localZ(),0,part.visibleLength(),part.curve(),part.miterSlope(),part.startMiter()));
                }
                for (int which = 0; which < 2; which++) {
                    boolean reversed = which == 1 && core.secondReversed(); int owner = which+2;
                    var ends = new ArrayList<NativeDiamondLayout.RailEnd>();
                    for (var end : which == 0 ? core.first() : core.second())
                        ends.add(new NativeDiamondLayout.RailEnd(reversed ? -end.side() : end.side(),
                                reversed ? paths.get(owner).length()-end.distance() : end.distance(),end.point(),
                                end.yaw()+(reversed ? Math.PI : 0),end.miterSlope()));
                    for (int side : new int[]{-1,1}) {
                        var selected = ends.stream().filter(end -> end.side() == side).sorted(Comparator.comparingDouble(NativeDiamondLayout.RailEnd::distance)).toList();
                        sections.get(owner).get(side).add(new Section(selected.getFirst(),selected.getLast()));
                    }
                }
            } catch (NativeDiamondLayout.InvalidLayout failure) {
                throw new NativeCrossoverLayout.InvalidLayout(NativeCrossoverLayout.Problem.valueOf(failure.problem().name()),
                        NativeLayoutDiagnostic.at("diamond_hardware","central",0,0,0,0).in("diamond","central"));
            }
        } else {
            // Only B and C are free ends; A and D already belong to their turnout cores.
            for (int owner = 0; owner < 2; owner++) for (int side : new int[]{-1,1}) {
                double boundary = owner == 0 ? paths.get(owner).length() : 0;
                var pose = curves.get(owner).sample(boundary);
                var end = new NativeDiamondLayout.RailEnd(side,boundary,
                        add(pose.position(),pose.lateral(),side*NativeCrossoverLayout.RAIL*scale),
                        Math.atan2(pose.tangent().x(),pose.tangent().z()),0);
                sections.get(owner).get(side).add(new Section(end,end));
            }
        }
        try (var stage = NativeLayoutProfile.stage(kind.label()+"/connections")) {
            for (int owner = 0; owner < paths.size(); owner++) for (int side : new int[]{-1,1}) {
                var ordered = sections.get(owner).get(side); ordered.sort(Comparator.comparingDouble(section -> section.start().distance()));
                for (int i = 1; i < ordered.size(); i++) {
                    var start = ordered.get(i-1).end(); var end = ordered.get(i).start();
                    if (end.distance()-start.distance() < .001*scale)
                        throw new NativeCrossoverLayout.InvalidLayout(NativeCrossoverLayout.Problem.SPACE,
                                NativeLayoutDiagnostic.at("core_connection_space",paths.get(owner).name(),side,start.distance(),end.distance(),.001*scale));
                    connections.addAll(NativeCrossoverGeometry.connect(curves.get(owner),start,end,origin,scale));
                }
            }
        }
        try (var stage = NativeLayoutProfile.stage(kind.label()+"/motionEnvelopes")) {
            if (complete) {
                try {
                    NativeCrossoverGeometry.validateMotion(switches, parts, origin, scale, hardwareOwners);
                } catch (NativeCrossoverLayout.InvalidLayout interference) {
                    if (policy == Policy.REFERENCE) throw interference;
                    if (stage != null) stage.value("referenceRetry", interference.diagnostic());
                    // Recheck the entire assembly with the reference motion before rejecting the layout.
                    return build(kind, geometry, paths, origin, gauge, true, Policy.REFERENCE);
                }
                if (kind == NativeCrossoverLayout.Kind.SINGLE)
                    NativeCrossoverGeometry.validateHardware(parts,scale,hardwareOwners,kind.controls);
            }
        }
        try (var stage = NativeLayoutProfile.stage(kind.label()+"/ballast")) {
            if (complete) ballast(parts,paths,scale);
        }
        return new Solution(List.copyOf(parts),List.copyOf(rails),Collections.unmodifiableMap(switches),List.copyOf(zones),List.copyOf(connections));
    }
    /** World-vertical 3 mm offset, independent of gauge scaling; includes the authored guard fasteners. */
    private static TrackVector lowerGuard(TrackVector position) {
        return new TrackVector(position.x(),position.y()-.003,position.z());
    }
    private static void ballast(List<NativeDiamondLayout.Part> parts, List<NativeCrossoverLayout.Path> paths, double scale) {
        var reference = paths.getFirst(); var range = NativeCrossoverLayout.referenceRange(paths);
        int first = (int)Math.floor(range[0]/(2*scale)), last = (int)Math.ceil((range[1]+.32*scale)/(2*scale));
        for (int row = first; row <= last; row++) {
            var frame = reference.extended(row*2*scale); var placed = new ArrayList<NativeDiamondLayout.Part>();
            String name = "ballast0"+(Math.floorMod(row,4)+1);
            for (int index = 0; index < paths.size(); index++) {
                var path = paths.get(index);
                double s = NativeCrossoverLayout.station(path,frame.point(),frame.tangent()); var node = path.at(s);
                s += dot(add(frame.point(),node.point(),-1),frame.tangent())/dot(node.tangent(),frame.tangent());
                if (s < -1e-6*scale || s >= path.length()+.32*scale) continue;
                node = path.extended(s); double yaw = Math.atan2(node.tangent().x(),node.tangent().z());
                var point = node.point();
                boolean coincident = placed.stream().anyMatch(part -> {
                    var delta = add(part.position(),point,-1);
                    // Deduplicate horizontal placements before applying the crossover's vertical offset.
                    return delta.x()*delta.x()+delta.z()*delta.z() < 1e-12*scale*scale
                            && Math.abs(Math.atan2(Math.sin(part.yaw()-yaw),Math.cos(part.yaw()-yaw))) < 1e-6;
                });
                if (!coincident) {
                    var position = index >= 2 ? new TrackVector(point.x(),point.y()-.010,point.z()) : point;
                    placed.add(new NativeDiamondLayout.Part(name,position,yaw,0,0,0));
                }
            }
            parts.addAll(placed);
        }
    }
    static List<TrackVector> footprint(NativeDiamondLayout.Part part, double scale) {
        if (!part.name().startsWith("ballast")) return NativeDiamondLayout.coreFootprint(part,scale).hull();
        var points = new ArrayList<TrackVector>(); double c = Math.cos(part.yaw()), s = Math.sin(part.yaw());
        for (double x : new double[]{-3.29484863,3.29484863}) for (double z : new double[]{-.320431,1.820581})
            points.add(add(part.position(),new TrackVector(c*x+s*z,0,-s*x+c*z),scale));
        return points;
    }
}
