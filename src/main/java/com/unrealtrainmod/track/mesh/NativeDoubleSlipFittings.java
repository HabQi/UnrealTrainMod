/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import static com.unrealtrainmod.track.mesh.NativeRailCurve.add;

/** Authored r11 fittings and the space their baked mesh must leave around the movable rails. */
public record NativeDoubleSlipFittings(List<Placement> parts, List<Clearance> motion) {
    private static final double HALF_SLEEPER = 1.2391;
    private static final double HALF_BALLAST = 3.2948;
    private static final double FOOT_INNER = .075 - (NativeDiamondLayout.RAIL_X - NativeTurnoutLayout.RAIL_WORK_X);
    private static final double FOOT_OUTER = .1105;
    private static final int SWEEP_STEPS = 16;

    public record BedSection(TrackVector center, TrackVector lateral, double share) {}
    public record BedSpan(BedSection start, BedSection end) {}

    public record Placement(NativeDiamondLayout.Part part, double acrossScale, BedSpan bed) {
        public Matrix4f transform(TrackVector origin, double scale) {
            return part.transform(origin,scale).scale((float)acrossScale,1,1);
        }
        public boolean hardware() { return part.name().startsWith("wing") || part.name().startsWith("guard"); }
    }
    public record Clearance(List<TrackVector> hull, double floor) {}

    static NativeDoubleSlipFittings build(TrackGeometry first, TrackGeometry second, NativeDiamondLayout.SlipEnds ends,
                                         double scale, List<NativeDoubleSlipLayout.Control> controls) {
        var a = new NativeDiamondLayout.Path(first);
        var b = new NativeDiamondLayout.Path(second);
        var parts = new ArrayList<Placement>();
        supports(parts,a,b,ends.center().firstStation(),true,scale);
        supports(parts,b,a,ends.center().secondStation(),false,scale);
        beds(parts,a,b,ends.center().firstStation(),true,scale);
        beds(parts,b,a,ends.center().secondStation(),false,scale);
        try {
            for (var part : NativeDiamondLayout.slipHardware(a,b,ends,scale)) parts.add(new Placement(part,1,null));
        } catch (NativeDiamondLayout.InvalidLayout failure) {
            throw new NativeDoubleSlipSolver.InvalidLayout(NativeDoubleSlipSolver.Problem.valueOf(failure.problem().name()));
        }

        var motion = new ArrayList<Clearance>();
        for (var control : controls) for (var blade : control.blades()) sweep(motion,blade,scale);
        return new NativeDoubleSlipFittings(List.copyOf(parts),List.copyOf(motion));
    }

    private static void supports(List<Placement> parts, NativeDiamondLayout.Path path, NativeDiamondLayout.Path other,
                                 double center, boolean primary, double scale) {
        int start = (int)Math.ceil((.25*scale-center)/scale);
        int end = (int)Math.floor((path.length()-.25*scale-center)/scale);
        for (int index = start; index <= end; index++) {
            double station = center+index*scale;
            var pose = path.at(station);
            var pair = NativeDiamondLayout.sleeperRow(path,other,station,scale);
            boolean shared = pair != null && Math.abs(pair.across()) <= 2.2*scale
                    && pair.second() >= .25*scale && pair.second() <= other.length()-.25*scale;
            if (!primary && shared) continue;
            var anchor = shared ? add(pair.a(),add(pair.b(),pair.a(),-1),.5) : pose.position();
            double yaw = shared ? pair.yaw() : NativeDoubleSlipLayout.yaw(pose.tangent());
            double extra = shared ? Math.abs(pair.across())/scale : 0;
            var part = place(shared ? "sleeper_point02L" : "sleeper_point01",anchor,yaw,.25);
            parts.add(new Placement(part,1+extra/(2*HALF_SLEEPER),null));
        }
    }

    private static void beds(List<Placement> parts, NativeDiamondLayout.Path path, NativeDiamondLayout.Path other,
                             double center, boolean primary, double scale) {
        double pitch = 1.5*scale;
        int index = (int)Math.floor(-center/pitch);
        double from = 0;
        var start = bedSection(path,other,from,primary,scale);
        while (from < path.length()) {
            double to = Math.min(path.length(),center+(index+1)*pitch);
            var end = bedSection(path,other,to,primary,scale);
            if (to-from > 1e-5*scale && (primary || start.share() < 1 || end.share() < 1)) {
                String name = "ballast0"+(1+Math.floorMod(index,4));
                parts.add(new Placement(place(name,start.center(),0,0),1,new BedSpan(start,end)));
            }
            from = to;
            start = end;
            index++;
        }
    }

    private static BedSection bedSection(NativeDiamondLayout.Path path, NativeDiamondLayout.Path other,
                                         double station, boolean primary, double scale) {
        var pose = path.at(station);
        var pair = NativeDiamondLayout.sleeperRow(path,other,station,scale);
        if (pair == null) return new BedSection(pose.position(),pose.lateral(),0);
        double separation = Math.abs(pair.across())/scale;
        // Blend the shared bed into both approach beds as their rails separate.
        double t = Math.clamp((separation-2.2)/1.5,0,1);
        double share = 1-t*t*(3-2*t);
        if (!primary) return new BedSection(pose.position(),pose.lateral(),share);
        var across = new TrackVector(Math.cos(pair.yaw()),0,-Math.sin(pair.yaw()));
        var center = add(pair.a(),across,.5*share*pair.across());
        var lateral = add(pose.lateral(),add(across,pose.lateral(),-1),share);
        return new BedSection(center,NativeRailCurve.multiply(lateral,1+share*separation/(2*HALF_BALLAST)),share);
    }

    private static NativeDiamondLayout.Part place(String name, TrackVector center, double yaw, double localZ) {
        long chunk = ChunkPos.asLong((int)Math.floor(center.x()/16),(int)Math.floor(center.z()/16));
        return new NativeDiamondLayout.Part(name,center,yaw,0,localZ,chunk);
    }

    private static void sweep(List<Clearance> result, NativeDoubleSlipLayout.Blade blade, double scale) {
        var links = blade.links();
        var joints = new NativeTurnoutLayout.Motion[links.size()];
        var groups = new ArrayList<List<TrackVector>>();
        int[] section = new int[links.size()];
        double run = 0, acceleration = 0, maxRotation = 0;
        for (int i = 0; i < links.size(); i++) {
            if (i == 0 || run >= .75*scale) { groups.add(new ArrayList<>()); run = 0; }
            section[i] = groups.size()-1;
            var link = links.get(i);
            double length = NativeDoubleSlipLayout.length(add(link.strip().end().point(),link.strip().start().point(),-1));
            run += length;
            acceleration += length*link.rotation()*link.rotation();
            maxRotation = Math.max(maxRotation,Math.abs(link.rotation()));
        }
        NativeTurnoutLayout.Motion next = null;
        for (int i = links.size()-1; i >= 0; i--) {
            var link = links.get(i); var strip = link.strip();
            var chord = add(strip.end().point(),strip.start().point(),-1);
            next = NativeTurnoutLayout.Motion.horizontal(next,strip.end().point(),NativeDoubleSlipLayout.length(chord),
                    NativeDoubleSlipLayout.yaw(chord),link.rotation(),false);
            joints[i] = next;
        }
        // Bound each corner between angular samples by the maximum second derivative of the link chain.
        double error = (acceleration+FOOT_OUTER*scale*maxRotation*maxRotation)/(8*SWEEP_STEPS*SWEEP_STEPS);
        double margin = .008*scale+error;
        for (int i = 0; i < links.size(); i++) {
            var link = links.get(i); var strip = link.strip();
            var group = groups.get(section[i]);
            for (int step = 0; step <= SWEEP_STEPS; step++) {
                float phase = step/(float)SWEEP_STEPS;
                var a = joints[i].start(phase);
                var b = i+1 == links.size() ? strip.end().point() : joints[i+1].start(phase);
                railCorners(group,a,strip.start(),strip.side(),phase*link.rotation(),scale,margin);
                railCorners(group,b,strip.end(),strip.side(),phase*link.rotation(),scale,margin);
            }
        }
        double floor = links.getFirst().strip().start().point().y()-.180*scale;
        for (var group : groups) result.add(new Clearance(NativeDiamondLayout.footprint(group).hull(),floor));
    }

    private static void railCorners(List<TrackVector> output, TrackVector point, NativeDoubleSlipLayout.Section section,
                                    int side, double rotation, double scale, double margin) {
        var lateral = NativeDoubleSlipLayout.lateral(section.tangent());
        double c = Math.cos(rotation), s = Math.sin(rotation);
        var turned = new TrackVector(c*lateral.x()+s*lateral.z(),0,c*lateral.z()-s*lateral.x());
        double outer = FOOT_OUTER*section.width()/NativeDoubleSlipLayout.HEAD_WIDTH;
        for (double across : new double[]{-FOOT_INNER,outer}) {
            var corner = add(point,turned,across*side*scale);
            for (int x : new int[]{-1,1}) for (int z : new int[]{-1,1})
                output.add(new TrackVector(corner.x()+x*margin,corner.y(),corner.z()+z*margin));
        }
    }

    public static List<Clearance> railheads(List<NativeDoubleSlipLayout.Strip> strips, double scale) {
        var result = new ArrayList<Clearance>(strips.size());
        for (var strip : strips) {
            var points = new ArrayList<TrackVector>(4);
            for (var section : List.of(strip.start(),strip.end())) {
                points.add(section.point());
                points.add(add(section.point(),NativeDoubleSlipLayout.lateral(section.tangent()),
                        strip.side()*section.width()*scale));
            }
            result.add(new Clearance(NativeDiamondLayout.footprint(points).hull(),strip.start().point().y()-.065*scale));
        }
        return List.copyOf(result);
    }
}
