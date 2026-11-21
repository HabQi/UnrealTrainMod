/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import static com.unrealtrainmod.track.mesh.NativeRailCurve.add;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.dot;

/** Authored r11 fittings and the space their baked mesh must leave around the movable rails. */
public record NativeDoubleSlipFittings(List<Placement> parts) {
    private static final double SLEEPER_PITCH = NativeDiamondLayout.ORDINARY_PERIOD/3;
    private static final double BED_PERIOD = 4*SLEEPER_PITCH;
    // The r11 support fasteners span ±0.894324 m, inside the wider concrete ends.
    private static final double FASTENER_HALF_WIDTH = .895;
    private static final double SLEEPER_OVERLAP_OFFSET = .002;
    private static final double BED_OVERLAP_OFFSET = .010;

    public record BedSpan(List<TrackGeometry> route, double start, double drop) {
        public TrackPose sample(double offset) {
            double station = start+offset;
            for (var path : route) {
                if (station <= path.getLength()) return extendedPose(path,station);
                station -= path.getLength();
            }
            var last = route.getLast();
            return extendedPose(last,last.getLength()+station);
        }

        private static TrackPose extendedPose(TrackGeometry path, double station) {
            double at = Math.clamp(station,0,path.getLength());
            var pose = path.sample(at);
            if (at == station) return pose;
            return new TrackPose(add(pose.position(),pose.tangent(),station-at),
                    pose.tangent(),pose.lateral(),pose.normal(),0,pose.gauge());
        }
    }

    public record Placement(NativeDiamondLayout.Part part, double alongScale, BedSpan bed) {
        public Matrix4f transform(TrackVector origin, double scale) {
            return part.transform(origin,scale).scale(1,1,(float)alongScale);
        }
        public boolean guard() { return part.name().startsWith("guard"); }
    }
    public record Clearance(List<TrackVector> hull, double floor) {}
    private record SleeperRow(double station, TrackVector position, double yaw, String model) {}

    static NativeDoubleSlipFittings build(NativeDiamondLayout.Path a, NativeDiamondLayout.Path b,
                                         List<TrackGeometry> firstSlip, List<TrackGeometry> secondSlip,
                                         NativeDiamondLayout.SlipEnds ends, NativeDiamondLayout.SlipFrogs frogs,
                                         double scale) {
        var firstBend = new NativeDiamondLayout.Path(firstSlip.get(1));
        var secondBend = new NativeDiamondLayout.Path(secondSlip.get(1));
        var hardware = new ArrayList<Placement>();
        for (var part : frogs.wings()) hardware.add(new Placement(part,1,null));
        for (var rail : frogs.rails()) hardware.add(new Placement(rail.nose(),rail.noseLengthScale(),null));
        var core = hardware.stream().map(part -> NativeDiamondLayout.coreFootprint(part.part(),scale)).toList();
        var parts = new ArrayList<Placement>();
        var firstRows = new ArrayList<SleeperRow>();
        var secondRows = new ArrayList<SleeperRow>();
        supportRows(firstRows, secondRows, a, b, firstBend, secondBend, ends.center().firstStation(), true, scale);
        supportRows(secondRows, firstRows, b, a, firstBend, secondBend, ends.center().secondStation(), false, scale);
        placeSleepers(parts,a,b,firstRows,frogs,false,core,scale);
        placeSleepers(parts,b,a,secondRows,frogs,true,core,scale);
        beds(parts, firstSlip, 0, scale);
        beds(parts, secondSlip, BED_OVERLAP_OFFSET, scale);
        parts.addAll(hardware);

        return new NativeDoubleSlipFittings(List.copyOf(parts));
    }

    private static void supportRows(List<SleeperRow> rows, List<SleeperRow> paired, NativeDiamondLayout.Path path, NativeDiamondLayout.Path other,
                                 NativeDiamondLayout.Path firstBend, NativeDiamondLayout.Path secondBend,
                                 double center, boolean primary, double scale) {
        int start = (int)Math.ceil((.25*scale-center)/scale);
        int end = (int)Math.floor((path.length()-.25*scale-center)/scale);
        for (int index = start; index <= end; index++) {
            double station = center+index*scale;
            var pair = NativeDiamondLayout.sleeperRow(path,other,station,scale);
            if (pair == null || pair.second() < .25*scale || pair.second() > other.length()-.25*scale) {
                var pose = path.at(station);
                rows.add(new SleeperRow(station,pose.position(),NativeDoubleSlipLayout.yaw(pose.tangent()),"sleeper_point01"));
                continue;
            }
            if (!primary) continue;

            var forward = new TrackVector(Math.sin(pair.yaw()), 0, Math.cos(pair.yaw()));
            var first = pair.a();
            var second = other.at(pair.second()).position();
            var firstSeat = curveSeat(firstBend, first, forward, scale);
            var secondSeat = curveSeat(secondBend, first, forward, scale);
            if (firstSeat != null && secondSeat != null) {
                first = firstSeat;
                second = secondSeat;
            }

            double separation = dot(add(second, first, -1), NativeDoubleSlipLayout.lateral(forward));
            if (Math.abs(separation) >= 2*NativeDiamondLayout.SLEEPER_HALF_WIDTH*scale) {
                rows.add(new SleeperRow(station,first,pair.yaw(),"sleeper_point01"));
                paired.add(new SleeperRow(pair.second(),second,pair.yaw(),"sleeper_point01"));
            } else {
                // Retain the authored gauge. Separate the overlapping concrete surfaces in height.
                boolean right = separation >= 0;
                rows.add(new SleeperRow(station,first,pair.yaw(),right ? "sleeper_point02R" : "sleeper_point02L"));
                var lowered = new TrackVector(second.x(),second.y()-SLEEPER_OVERLAP_OFFSET,second.z());
                paired.add(new SleeperRow(pair.second(),lowered,pair.yaw(),right ? "sleeper_point02L" : "sleeper_point02R"));
            }
        }
    }

    private static void placeSleepers(List<Placement> parts, NativeDiamondLayout.Path path, NativeDiamondLayout.Path other,
            List<SleeperRow> rows, NativeDiamondLayout.SlipFrogs frogs, boolean second,
            List<NativeDiamondLayout.Footprint> core, double scale) {
        rows.sort(Comparator.comparingDouble(SleeperRow::station));
        var tails = frogs.rails().stream().filter(rail -> rail.second() == second)
                .mapToDouble(rail -> rail.tail().distance()).sorted().toArray();
        double period = NativeDiamondLayout.ORDINARY_PERIOD*scale;
        double extent = NativeDiamondLayout.SLEEPER_HALF_LENGTH*scale;
        double fullWidth = NativeDiamondLayout.SLEEPER_HALF_WIDTH;
        var otherFasteners = NativeDiamondLayout.sleeperFootprints(other,FASTENER_HALF_WIDTH,scale);
        List<NativeDiamondLayout.Footprint> otherSleepers =
                second ? NativeDiamondLayout.sleeperFootprints(other,fullWidth,scale) : List.of();
        int first = 0, last = rows.size()-1;
        for (int direction : new int[]{-1,1}) {
            double joint = direction < 0 ? tails[0] : tails[tails.length-1];
            double end = direction < 0 ? 0 : path.length();
            for (int index = direction < 0 ? last : first; index+direction >= first && index+direction <= last; index += direction) {
                var retained = rows.get(index);
                double center = NativeDiamondLayout.ordinarySleeperCenter(path,retained.station(),retained.yaw(),direction,scale);
                if (direction*(center-joint) < extent) continue;
                int count = 1+(int)Math.floor((direction*(end-center)-SLEEPER_PITCH*scale)/period);
                if (count <= 0) break;
                var replaced = rows.get(index+direction);
                double candidate = NativeDiamondLayout.ordinarySleeperCenter(path,replaced.station(),replaced.yaw(),direction,scale);
                if (!NativeDiamondLayout.separate(NativeDiamondLayout.moduleFootprint(path,candidate,FASTENER_HALF_WIDTH,scale),
                        otherFasteners,core)) continue;

                // Start one two-sleeper group earlier, keeping the transition module clear of the frog.
                var run = new ArrayList<Placement>(count);
                for (int group = 0; group < count; group++) {
                    double station = center+direction*group*period;
                    var full = NativeDiamondLayout.moduleFootprint(path,station,fullWidth,scale);
                    var fasteners = NativeDiamondLayout.moduleFootprint(path,station,FASTENER_HALF_WIDTH,scale);
                    if (core.stream().anyMatch(full::intersects) || otherFasteners.stream().anyMatch(fasteners::intersects)) break;
                    var pose = path.at(station);
                    var point = pose.position();
                    if (otherSleepers.stream().anyMatch(full::intersects))
                        point = new TrackVector(point.x(),point.y()-SLEEPER_OVERLAP_OFFSET,point.z());
                    run.add(new Placement(place("sleeper_support",point,NativeDoubleSlipLayout.yaw(pose.tangent()),0),1,null));
                }
                if (run.size() != count) continue;
                parts.addAll(run);
                if (direction < 0) first = index;
                else last = index;
                break;
            }
        }
        for (int i = first; i <= last; i++) {
            var row = rows.get(i);
            parts.add(new Placement(place(row.model(),row.position(),row.yaw(),.25),1,null));
        }
    }

    private static TrackVector curveSeat(NativeDiamondLayout.Path curve, TrackVector origin, TrackVector forward, double scale) {
        double low = .25*scale, high = curve.length()-.25*scale;
        if (high <= low || dot(add(curve.at(low).position(), origin, -1), forward) > 0
                || dot(add(curve.at(high).position(), origin, -1), forward) < 0) return null;
        // Intersect the curve centerline with the existing sleeper row.
        for (int i = 0; i < 32; i++) {
            double mid = (low+high)/2;
            if (dot(add(curve.at(mid).position(), origin, -1), forward) < 0) low = mid;
            else high = mid;
        }
        return curve.at((low+high)/2).position();
    }

    private static void beds(List<Placement> parts, List<TrackGeometry> route, double drop, double scale) {
        double pitch = BED_PERIOD*scale;
        double length = route.stream().mapToDouble(TrackGeometry::getLength).sum();
        for (int index = 0; index*pitch < length-1e-5*scale; index++) {
            var span = new BedSpan(route,index*pitch,drop);
            String name = "ballast0"+(1+index%4);
            parts.add(new Placement(place(name,span.sample(0).position(),0,0),1,span));
        }
    }

    private static NativeDiamondLayout.Part place(String name, TrackVector center, double yaw, double localZ) {
        long chunk = ChunkPos.asLong((int)Math.floor(center.x()/16),(int)Math.floor(center.z()/16));
        return new NativeDiamondLayout.Part(name,center,yaw,0,localZ,chunk);
    }

    public static List<Clearance> railheads(List<NativeDoubleSlipLayout.Strip> strips, double scale) {
        var result = new ArrayList<Clearance>(strips.size());
        for (var strip : strips) {
            var points = new ArrayList<TrackVector>(4);
            for (var section : List.of(strip.start(),strip.end())) {
                points.add(section.point());
                points.add(add(section.point(),section.across(),
                        strip.side()*section.width()*scale));
            }
            result.add(new Clearance(NativeDiamondLayout.footprint(points).hull(),strip.start().point().y()-.065*scale));
        }
        return List.copyOf(result);
    }
}
