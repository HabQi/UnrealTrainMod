/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.awt.geom.Area;
import java.util.*;
import org.joml.Vector3f;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.*;
import static com.unrealtrainmod.track.mesh.NativePlanarGeometry.*;

/** Source-profile rail connections between the ordinary turnout and diamond's actual end sections. */
public final class NativeCrossoverGeometry {
    private NativeCrossoverGeometry() {}
    public record Result(Map<String,IndexedMesh> fixed, Map<String,IndexedMesh> blades) {
        public Result { fixed = Collections.unmodifiableMap(new LinkedHashMap<>(fixed)); blades = Map.copyOf(blades); }
    }
    public record Rail(TrackVector position, double yaw, int side, double length, NativeRailCurve curve,
                       double startMiter, double endMiter) {}

    static List<Rail> connect(TrackGeometry path, NativeDiamondLayout.RailEnd start, NativeDiamondLayout.RailEnd end,
                              TrackVector origin, double scale) {
        double from = start.distance(), to = end.distance(), offset = start.side()*NativeCrossoverLayout.RAIL*scale;
        var reference = NativeFixedRails.reference(path,from,to,offset,start.point(),end.point(),start.yaw(),end.yaw(),scale);
        var cuts = NativeFixedRails.cuts(reference,scale,start.miterSlope(),end.miterSlope()); var result = new ArrayList<Rail>();
        for (int part = 1; part < cuts.size(); part++) {
            double a = cuts.get(part-1), b = cuts.get(part), length = (b-a)/scale;
            var first = reference.at(a); var anchor = first.point(); double yaw = Math.atan2(first.first().x(),first.first().z());
            double startMiter = part == 1 ? start.miterSlope() : 0, endMiter = part+1 == cuts.size() ? end.miterSlope() : 0;
            var curve = reference.local(a,b,anchor,yaw,0,scale,length,.075*scale*Math.abs(startMiter),.075*scale*Math.abs(endMiter));
            result.add(new Rail(add(anchor,origin,-1),yaw,start.side(),length,curve,startMiter,endMiter));
        }
        return List.copyOf(result);
    }

    /** Check actual ordinary-turnout links, including their moving connector rails, against foreign controls and hardware. */
    static void validateMotion(Map<String,NativeTurnoutLayout> switches, List<NativeDiamondLayout.Part> parts, TrackVector origin, double scale, Map<NativeDiamondLayout.Part,Integer> owners) {
        var controls = List.copyOf(switches.keySet());
        var envelopes = new ArrayList<Area>();
        for (var layout : switches.values()) {
            var areas = new ArrayList<Area>(); var vertex = new Vector3f();
            for (var part : layout.parts()) {
                var points = new ArrayList<Point>();
                double length = part.bladeLength() > 0 ? part.bladeLength() : part.visibleLength();
                var corners = motionCorners(part,length);
                for (int step = 0; step <= 16; step++) {
                    var transform = part.transform(origin,scale,step/16f);
                    for (var corner : corners) {
                        transform.transformPosition(vertex.set((float)corner.x(),(float)corner.y(),(float)corner.z()));
                        points.add(new Point(vertex.x,vertex.z));
                    }
                }
                areas.add(polygon(hull(points)));
            }
            envelopes.add(union(areas));
        }
        for (int a = 0; a < envelopes.size(); a++) for (int b = a+1; b < envelopes.size(); b++) {
            var overlap = new Area(envelopes.get(a)); overlap.intersect(envelopes.get(b));
            if (!overlap.isEmpty()) throw new NativeCrossoverLayout.InvalidLayout(NativeCrossoverLayout.Problem.SPACE,
                    NativeLayoutDiagnostic.at("blade_motion_interference","links",0,0,0,0)
                            .in(controls.get(a),controls.get(b)));
        }
        for (var part : parts) if (part.name().startsWith("wing") || part.name().startsWith("guard") || part.name().startsWith("nose")) {
            var footprint = polygon(NativeCrossoverParts.footprint(part,scale).stream().map(p -> new Point(p.x(),p.z())).toList());
            for (int control = 0; control < envelopes.size(); control++) {
                if (owners.getOrDefault(part,-1) == control) continue;
                var overlap = new Area(envelopes.get(control)); overlap.intersect(footprint);
                if (!overlap.isEmpty()) throw new NativeCrossoverLayout.InvalidLayout(NativeCrossoverLayout.Problem.SPACE,
                        NativeLayoutDiagnostic.at("blade_hardware_interference",part.name(),0,0,0,0)
                                .in(controls.get(control),part.name()));
            }
        }
    }

    private static List<TrackVector> motionCorners(NativeTurnoutLayout.Part part, double length) {
        // Pad between angular samples as well as the source foot width.
        double margin = .001;
        var corners = new ArrayList<TrackVector>();
        if (part.railBend() == null || !part.railBend().longSection()) {
            for (double x : new double[]{part.localX()-.07479225-margin,part.localX()+.07479225+margin})
                for (double z : new double[]{part.localZ()-margin,part.localZ()+length+margin})
                    corners.add(new TrackVector(x,0,z));
            return corners;
        }
        // The longer strip is linear between two swept end sections. Include
        // their tilted feet when testing clearance against the other cores.
        var curve = part.railBend().curve();
        var origin = new TrackVector(part.localX(),0,part.localZ());
        for (double station : new double[]{0,length}) {
            var knot = curve.at(station);
            var tangent = multiply(knot.first(),1/Math.sqrt(dot(knot.first(),knot.first())));
            var lateral = cross(curve.up(),tangent);
            lateral = multiply(lateral,1/Math.sqrt(dot(lateral,lateral)));
            var up = cross(tangent,lateral);
            var center = add(knot.point(),origin,1);
            for (double x : new double[]{-.075,.075}) for (double y : new double[]{-.176,0}) {
                var p = add(add(center,lateral,x),up,y);
                for (double dx : new double[]{-margin,margin}) for (double dz : new double[]{-margin,margin})
                    corners.add(new TrackVector(p.x()+dx,p.y(),p.z()+dz));
            }
        }
        return corners;
    }
    /** Single crossovers have no central crossing hardware that can legitimately overlap two cores. */
    static void validateHardware(List<NativeDiamondLayout.Part> parts, double scale,
                                 Map<NativeDiamondLayout.Part,Integer> owners, List<String> controls) {
        var footprints = parts.stream().map(part -> polygon(NativeCrossoverParts.footprint(part,scale)
                .stream().map(p -> new Point(p.x(),p.z())).toList())).toList();
        for (int a = 0; a < parts.size(); a++) for (int b = a+1; b < parts.size(); b++) {
            int first = owners.get(parts.get(a)), second = owners.get(parts.get(b));
            if (first == second || !footprints.get(a).getBounds2D().intersects(footprints.get(b).getBounds2D())) continue;
            var overlap = new Area(footprints.get(a)); overlap.intersect(footprints.get(b));
            if (!overlap.isEmpty()) throw new NativeCrossoverLayout.InvalidLayout(NativeCrossoverLayout.Problem.SPACE,
                    NativeLayoutDiagnostic.at("core_hardware_interference",parts.get(a).name()+"|"+parts.get(b).name(),0,0,0,0)
                            .in(controls.get(first),controls.get(second)));
        }
    }
    private static List<Point> hull(List<Point> points) {
        var sorted = new ArrayList<>(points); sorted.sort(Comparator.comparingDouble(Point::x).thenComparingDouble(Point::z));
        var result = new ArrayList<Point>();
        for (var p : sorted) { while (result.size() >= 2 && turn(result.get(result.size()-2),result.getLast(),p) <= 0) result.removeLast(); result.add(p); }
        int lower = result.size();
        for (var p : sorted.reversed()) { while (result.size() > lower && turn(result.get(result.size()-2),result.getLast(),p) <= 0) result.removeLast(); result.add(p); }
        result.removeLast(); return result;
    }
    private static double turn(Point a, Point b, Point c) { return (b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x()); }
}
