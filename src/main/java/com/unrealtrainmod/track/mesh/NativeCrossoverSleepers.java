/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.List;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.*;

/** One global two-sleeper phase, with the turnout/diamond's authored support parts. */
final class NativeCrossoverSleepers {
    private static final double HALF_WIDTH = 1.23913071;
    private NativeCrossoverSleepers() {}
    private record Placement(int path, NativeCrossoverLayout.Node node) {}

    static List<NativeCrossoverLayout.Sleeper> build(List<NativeCrossoverLayout.Path> paths,
                                                  List<NativeCrossoverLayout.SleeperZone> zones, double scale) {
        var reference = paths.getFirst(); var output = new ArrayList<NativeCrossoverLayout.Sleeper>();
        var range = NativeCrossoverLayout.referenceRange(paths); double first = range[0], last = range[1];
        // Each source contains two sleepers at Z=0 and Z=0.5, placed by their midpoint.
        for (double s = Math.floor(first/scale)*scale-.25*scale; s-.25*scale < last; s += scale) {
            var row = reference.extended(s); var pending = new ArrayList<Placement>();
            for (int p = 0; p < paths.size(); p++) {
                var path = paths.get(p); double station = NativeCrossoverLayout.station(path,row.point(),row.tangent());
                var node = path.at(station);
                station += dot(add(row.point(),node.point(),-1),row.tangent())/dot(node.tangent(),row.tangent());
                if (onPath(path,station,scale)) pending.add(new Placement(p,path.extended(station)));
            }
            while (!pending.isEmpty()) {
                int a = -1, b = -1; double closest = Math.pow(2*HALF_WIDTH*scale,2);
                for (int i = 0; i < pending.size(); i++) for (int j = i+1; j < pending.size(); j++) {
                    if (pending.get(i).path() < 2 && pending.get(j).path() < 2) continue;
                    var delta = add(pending.get(i).node().point(),pending.get(j).node().point(),-1);
                    double distance = dot(delta,delta);
                    if (distance < closest) { a = i; b = j; closest = distance; }
                }
                if (a < 0) {
                    var item = pending.removeFirst();
                    place(output,item,Math.atan2(item.node().tangent().x(),item.node().tangent().z()),-1,0,paths,zones,scale);
                    continue;
                }
                var own = pending.get(a); var other = pending.get(b); var path = paths.get(other.path());
                // Solve the same transverse bisector as the turnout and diamond, with end extension.
                double radius = paths.get(own.path()).length()+path.length(), lo = -radius, hi = path.length()+radius;
                for (int iteration = 0; iteration < 40; iteration++) {
                    double mid = (lo+hi)/2; var pose = path.extended(mid);
                    if (dot(add(pose.point(),own.node().point(),-1),add(pose.tangent(),own.node().tangent(),1)) < 0) lo = mid; else hi = mid;
                }
                double station = (lo+hi)/2;
                pending.remove(b); pending.remove(a);
                if (!onPath(path,station,scale)) {
                    for (var item : List.of(own,other)) place(output,item,Math.atan2(item.node().tangent().x(),item.node().tangent().z()),-1,0,paths,zones,scale);
                    continue;
                }
                other = new Placement(other.path(),path.extended(station));
                var tangent = add(own.node().tangent(),other.node().tangent(),1);
                double yaw = Math.atan2(tangent.x(),tangent.z());
                var lateral = new TrackVector(Math.cos(yaw),0,-Math.sin(yaw));
                var delta = add(other.node().point(),own.node().point(),-1); double across = dot(delta,lateral);
                var residual = add(delta,lateral,-across);
                if (dot(own.node().tangent(),other.node().tangent()) < .5 || dot(residual,residual) > 1e-10*scale*scale)
                    throw new NativeCrossoverLayout.InvalidLayout(NativeCrossoverLayout.Problem.SPACE,
                            NativeLayoutDiagnostic.at("sleeper_pair",paths.get(own.path()).name(),0,own.node().station(),station,1e-5*scale));
                place(output,own,yaw,other.path(),across,paths,zones,scale);
                place(output,other,yaw,own.path(),-across,paths,zones,scale);
            }
        }
        return List.copyOf(output);
    }
    private static boolean onPath(NativeCrossoverLayout.Path path, double station, double scale) {
        return station+.25*scale >= -1e-5*scale && station-.25*scale < path.length();
    }
    private static void place(List<NativeCrossoverLayout.Sleeper> output, Placement item, double yaw, int partner,
                              double across, List<NativeCrossoverLayout.Path> paths,
                              List<NativeCrossoverLayout.SleeperZone> zones, double scale) {
        double length = paths.get(item.path()).length(), station = item.node().station();
        for (var zone : zones) if (zone.path() == item.path()) {
            double directed = zone.reversed() ? length-station : station;
            var reference = paths.get(zone.reference());
            var forward = new TrackVector(Math.sin(yaw),0,Math.cos(yaw));
            double referenceStation = NativeCrossoverLayout.station(reference,item.node().point(),forward);
            var frame = reference.at(referenceStation);
            referenceStation += dot(add(item.node().point(),frame.point(),-1),forward)/dot(frame.tangent(),forward);
            double directedReference = zone.reversed() ? reference.length()-referenceStation : referenceStation;
            // Both branches change to point01 on the main line's same reference row.
            double row = Math.floor((directedReference+(.25+1e-5)*scale)/(2*scale))*2*scale;
            String kind = NativeTurnoutLayout.sleeperKind(row,zone.frog(),directed,zone.tip(),zone.heel(),scale);
            if (!kind.endsWith("01")) {
                output.add(new NativeCrossoverLayout.Sleeper(kind+(zone.inner() > 0 ? "R" : "L"),
                        item.node().point(),yaw+(zone.reversed() ? Math.PI : 0)));
                return;
            }
        }
        boolean diamond = item.path() >= 2 && partner >= 2;
        // Coincident rows use the same stable path order as NativeDiamondLayout.
        boolean right = across > 1e-5*scale || Math.abs(across) <= 1e-5*scale && item.path() < partner;
        String name = diamond ? "sleeper_point02"+(right ? "R" : "L") : "sleeper_point01";
        output.add(new NativeCrossoverLayout.Sleeper(name,item.node().point(),yaw));
    }
}
