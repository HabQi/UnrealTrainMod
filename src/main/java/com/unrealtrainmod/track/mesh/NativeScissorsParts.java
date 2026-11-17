/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.*;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.*;

/** Fixed source hardware and the ordinary turnout's four-phase ballast sequence. */
final class NativeScissorsParts {
    private NativeScissorsParts() {}
    record Solution(List<NativeDiamondLayout.Part> parts, List<NativeTurnoutLayout.Part> rails,
                    Map<String,NativeTurnoutLayout> switches, List<NativeScissorsLayout.SleeperZone> zones, List<NativeScissorsGeometry.Rail> connections) {}
    private record Section(NativeDiamondLayout.RailEnd start, NativeDiamondLayout.RailEnd end) {}
    static Solution build(TrackAssemblyDefinition.Geometry geometry,
            List<NativeScissorsLayout.Path> paths, TrackVector origin, double gauge) {
        double scale = gauge/1.435; var parts = new ArrayList<NativeDiamondLayout.Part>();
        var rails = new ArrayList<NativeTurnoutLayout.Part>(); var switches = new LinkedHashMap<String,NativeTurnoutLayout>();
        var hardwareOwners = new HashMap<NativeDiamondLayout.Part,Integer>();
        var zones = new ArrayList<NativeScissorsLayout.SleeperZone>(); var connections = new ArrayList<NativeScissorsGeometry.Rail>();
        var sections = new ArrayList<Map<Integer,List<Section>>>();
        for (int i = 0; i < paths.size(); i++) sections.add(Map.of(-1,new ArrayList<>(),1,new ArrayList<>()));
        var curves = NativeScissorsLayout.PATHS.stream().map(name -> geometry.paths().get(name).getFirst()).toList();
        int[][] pairs = {{0,2},{0,3},{1,3},{1,2}};
        for (int control = 0; control < pairs.length; control++) {
            int[] owners = pairs[control]; boolean reversed = control == 1 || control == 3;
            TrackGeometry main = curves.get(owners[0]), branch = curves.get(owners[1]);
            if (reversed) { main = main.reversed(); branch = branch.reversed(); }
            try (var stage = NativeLayoutProfile.stage("scissors/core/"+NativeScissorsLayout.CONTROLS.get(control))) {
                if (stage != null) { stage.value("mainPath", paths.get(owners[0]).name()); stage.value("branchPath", paths.get(owners[1]).name()); stage.value("reversed", reversed); }
                int firstPart = parts.size();
                var core = NativeTurnoutLayout.core(main,branch,gauge); var moving = new ArrayList<NativeTurnoutLayout.Part>();
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
                            parts.add(new NativeDiamondLayout.Part("guard"+side+"_short",lowerGuard(position),part.yaw(),part.localX(),part.localZ(),0));
                        } else parts.add(new NativeDiamondLayout.Part(part.name(),position,part.yaw(),part.localX(),part.localZ(),0));
                    }
                }
                for (int i = firstPart; i < parts.size(); i++) hardwareOwners.put(parts.get(i),control);
                switches.put(NativeScissorsLayout.CONTROLS.get(control),new NativeTurnoutLayout(origin,scale,moving,0));
                for (var zone : core.blades()) zones.add(new NativeScissorsLayout.SleeperZone(owners[zone.branch() ? 1 : 0],
                        owners[0],reversed,zone.inner(),zone.tip(),zone.heel(),zone.frog()));
                for (int which = 0; which < 2; which++) for (var exit : which == 0 ? core.main() : core.branch()) {
                    int owner = owners[which], side = reversed ? -exit.side() : exit.side();
                    double length = paths.get(owner).length(), distance = reversed ? length-exit.distance() : exit.distance();
                    var end = new NativeDiamondLayout.RailEnd(side,distance,exit.point(),exit.yaw()+(reversed ? Math.PI : 0),0);
                    double boundary = reversed ? length : 0; var pose = curves.get(owner).sample(boundary);
                    var port = new NativeDiamondLayout.RailEnd(side,boundary,add(pose.position(),pose.lateral(),side*NativeScissorsLayout.RAIL*scale),
                            Math.atan2(pose.tangent().x(),pose.tangent().z()),0);
                    boolean stock = exit.side() != core.blades().get(which).inner();
                    sections.get(owner).get(side).add(stock ? new Section(port,port)
                            : reversed ? new Section(end,port) : new Section(port,end));
                }
            } catch (NativeTurnoutLayout.InvalidLayout failure) {
                throw new NativeScissorsLayout.InvalidLayout(failure.problem() == NativeTurnoutLayout.Problem.ANGLE
                        ? NativeScissorsLayout.Problem.ANGLE : NativeScissorsLayout.Problem.SPACE,
                        failure.diagnostic().in(NativeScissorsLayout.CONTROLS.get(control),"turnout_core"));
            }
        }
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
            throw new NativeScissorsLayout.InvalidLayout(NativeScissorsLayout.Problem.valueOf(failure.problem().name()),
                    NativeLayoutDiagnostic.at("diamond_hardware","central",0,0,0,0).in("diamond","central"));
        }
        try (var stage = NativeLayoutProfile.stage("scissors/connections")) {
            for (int owner = 0; owner < paths.size(); owner++) for (int side : new int[]{-1,1}) {
                var ordered = sections.get(owner).get(side); ordered.sort(Comparator.comparingDouble(section -> section.start().distance()));
                for (int i = 1; i < ordered.size(); i++) {
                    var start = ordered.get(i-1).end(); var end = ordered.get(i).start();
                    if (end.distance()-start.distance() < .001*scale)
                        throw new NativeScissorsLayout.InvalidLayout(NativeScissorsLayout.Problem.SPACE,
                                NativeLayoutDiagnostic.at("core_connection_space",paths.get(owner).name(),side,start.distance(),end.distance(),.001*scale));
                    connections.addAll(NativeScissorsGeometry.connect(curves.get(owner),start,end,origin,scale));
                }
            }
        }
        try (var stage = NativeLayoutProfile.stage("scissors/motionEnvelopes")) {
            NativeScissorsGeometry.validateMotion(switches,parts,origin,scale,hardwareOwners);
        }
        try (var stage = NativeLayoutProfile.stage("scissors/ballast")) {
            ballast(parts,paths,scale);
        }
        return new Solution(List.copyOf(parts),List.copyOf(rails),Collections.unmodifiableMap(switches),List.copyOf(zones),List.copyOf(connections));
    }
    /** World-vertical 3 mm offset, independent of gauge scaling; includes the authored guard fasteners. */
    private static TrackVector lowerGuard(TrackVector position) {
        return new TrackVector(position.x(),position.y()-.003,position.z());
    }
    private static void ballast(List<NativeDiamondLayout.Part> parts, List<NativeScissorsLayout.Path> paths, double scale) {
        var reference = paths.getFirst(); var range = NativeScissorsLayout.referenceRange(paths);
        int first = (int)Math.floor(range[0]/(2*scale)), last = (int)Math.ceil((range[1]+.32*scale)/(2*scale));
        for (int row = first; row <= last; row++) {
            var frame = reference.extended(row*2*scale); var placed = new ArrayList<NativeDiamondLayout.Part>();
            String name = "ballast0"+(Math.floorMod(row,4)+1);
            for (int index = 0; index < paths.size(); index++) {
                var path = paths.get(index);
                double s = NativeScissorsLayout.station(path,frame.point(),frame.tangent()); var node = path.at(s);
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
