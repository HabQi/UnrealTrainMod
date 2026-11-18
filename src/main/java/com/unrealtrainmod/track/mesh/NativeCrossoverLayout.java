/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.*;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.*;

/** Shared whole-assembly layout for single and scissors crossovers. Fixed geometry is layout-local; moving layouts retain world anchors. */
public record NativeCrossoverLayout(Kind kind, TrackVector origin, double scale, List<Path> paths, List<NativeTurnoutLayout.Part> fixedRails,
        List<NativeDiamondLayout.Part> fixedParts, List<NativeCrossoverGeometry.Rail> connections, List<Sleeper> sleepers, Map<String, NativeTurnoutLayout> switches,
        Set<Long> chunks, AABB bounds, int samples) {
    public enum Kind {
        SINGLE(List.of("first_straight", "second_straight", "crossover"), List.of("switch_one", "switch_two"),
                List.of(new CoreSpec(0,2,false), new CoreSpec(1,2,true))),
        SCISSORS(List.of("first_straight", "second_straight", "first_crossover", "second_crossover"),
                List.of("switch_one", "switch_two", "switch_three", "switch_four"),
                List.of(new CoreSpec(0,2,false), new CoreSpec(0,3,true), new CoreSpec(1,3,false), new CoreSpec(1,2,true)));
        final List<String> paths, controls;
        final List<CoreSpec> cores;
        Kind(List<String> paths, List<String> controls, List<CoreSpec> cores) {
            this.paths = paths; this.controls = controls; this.cores = cores;
        }
        public List<String> controls() { return controls; }
        public String label() { return this == SINGLE ? "singleCrossover" : "scissors"; }
    }
    record CoreSpec(int main, int branch, boolean reversed) {}
    public static final double RAIL = 0.74875;
    public NativeCrossoverLayout {
        paths = List.copyOf(paths); fixedRails = List.copyOf(fixedRails); fixedParts = List.copyOf(fixedParts); connections = List.copyOf(connections); sleepers = List.copyOf(sleepers);
        switches = Collections.unmodifiableMap(new LinkedHashMap<>(switches)); chunks = Set.copyOf(chunks);
    }
    public enum Problem { INTERSECTION, ANGLE, SPACE }
    public static final class InvalidLayout extends IllegalArgumentException {
        private final Problem problem; private final NativeLayoutDiagnostic diagnostic;
        public InvalidLayout(Problem problem) { this(problem, NativeLayoutDiagnostic.at("global_"+problem.name(),"layout",0,0,0,0)); }
        public InvalidLayout(Problem problem, NativeLayoutDiagnostic diagnostic) {
            super(problem+" "+diagnostic); this.problem = problem; this.diagnostic = diagnostic;
        }
        public Problem problem() { return problem; }
        public NativeLayoutDiagnostic diagnostic() { return diagnostic; }
    }
    public record Node(double station, TrackVector point, TrackVector tangent) {
        public TrackVector lateral() { return new TrackVector(tangent.z(),0,-tangent.x()); }
        public TrackVector edge(double offset) { return add(point,lateral(),offset); }
    }
    public record Path(String name, List<Node> nodes, double length) {
        public Path { nodes = List.copyOf(nodes); }
        public Node at(double s) {
            s = Math.clamp(s,0,length); int lo = 0, hi = nodes.size()-1;
            while (hi-lo > 1) { int mid = (lo+hi)/2; if (nodes.get(mid).station() <= s) lo = mid; else hi = mid; }
            var a = nodes.get(lo); var b = nodes.get(hi); double t = (s-a.station())/(b.station()-a.station());
            var tangent = add(multiply(a.tangent(),1-t),b.tangent(),t);
            tangent = multiply(tangent,1/Math.sqrt(dot(tangent,tangent)));
            return new Node(s,add(multiply(a.point(),1-t),b.point(),t),tangent);
        }
        Node extended(double s) {
            var node = at(s);
            return new Node(s,add(node.point(),node.tangent(),s-Math.clamp(s,0,length)),node.tangent());
        }
    }
    public record Sleeper(String name, TrackVector center, double yaw) {}
    record SleeperZone(int path, int reference, boolean reversed, int inner, double tip, double heel, double frog) {}
    private record Hit(double a, double b, TrackVector point, double angle) {}
    public int partCount() { return switches.values().stream().mapToInt(s -> s.parts().size()).sum()+fixedRails.size()+connections.size()+fixedParts.size()+sleepers.size()+paths.stream().mapToInt(p -> p.nodes().size()).sum(); }

    public static NativeCrossoverLayout build(TrackAssemblyDefinition.Geometry geometry, double gauge, NativeLayoutProfile profile) {
        try (var stage = profile == null ? null : profile.open(kind(geometry).label()+"/layout")) {
            try { return build(geometry, gauge); }
            catch (RuntimeException | Error failure) { if (stage != null) stage.failure(failure); throw failure; }
        }
    }
    public static NativeCrossoverLayout build(TrackAssemblyDefinition.Geometry geometry, double gauge) {
        return build(geometry, gauge, true);
    }
    public static NativeCrossoverLayout candidate(TrackAssemblyDefinition.Geometry geometry, double gauge) {
        return build(geometry, gauge, false);
    }
    private static Kind kind(TrackAssemblyDefinition.Geometry geometry) {
        return geometry.paths().containsKey("crossover") ? Kind.SINGLE : Kind.SCISSORS;
    }
    private static NativeCrossoverLayout build(TrackAssemblyDefinition.Geometry geometry, double gauge, boolean complete) {
        var kind = kind(geometry); var names = kind.paths;
        if (!Double.isFinite(gauge) || gauge <= 0) throw new InvalidLayout(Problem.SPACE);
        double scale = gauge/1.435;
        var first = geometry.paths().get(names.getFirst());
        if (first == null || first.size() != 1) throw new InvalidLayout(Problem.INTERSECTION);
        var origin = first.getFirst().sample(0).position(); var paths = new ArrayList<Path>();
        try (var stage = NativeLayoutProfile.stage(kind.label()+"/pathSamplingAndIntersections")) {
            for (String name : names) {
                var items = geometry.paths().get(name);
                if (items == null || items.size() != 1) throw new InvalidLayout(Problem.INTERSECTION);
                var curve = items.getFirst(); var nodes = new ArrayList<Node>(); nodes.add(sample(curve,0,origin,gauge));
                int count = Math.max(2,(int)Math.ceil(curve.getLength()/(0.5*scale)));
                for (int i = 1; i <= count; i++) subdivide(curve,nodes.getLast(),sample(curve,curve.getLength()*i/count,origin,gauge),origin,gauge,scale,nodes);
                var path = new Path(name,nodes,curve.getLength());
                if (!hits(path,path,0,0,true).isEmpty()) fail(Problem.INTERSECTION,"self_intersection",name,0,0,path.length(),0,"layout");
                for (int side : new int[]{-1,1}) for (double offset : new double[]{RAIL-.075,RAIL,RAIL+.075})
                    if (!hits(path,path,side*offset*scale,side*offset*scale,true).isEmpty())
                        fail(Problem.SPACE,"offset_self_intersection",name,side,0,path.length(),0,"layout");
                for (var node : nodes) if (Math.abs(curve.sample(node.station()).curvature())*(RAIL+0.075)*scale >= 1)
                    fail(Problem.SPACE,"offset_cusp",name,0,node.station(),node.station(),(RAIL+0.075)*scale,"layout");
                paths.add(path);
            }
            for (int a = 0; a < paths.size(); a++) for (int b = a+1; b < paths.size(); b++) {
                var intersections = hits(paths.get(a),paths.get(b),0,0,false);
                if (kind == Kind.SCISSORS && a == 2 && b == 3) {
                    if (intersections.size() != 1) fail(Problem.INTERSECTION,"central_crossing",names.get(a)+"|"+names.get(b),0,0,intersections.size(),1,"diamond");
                    angle(intersections.getFirst(),"diamond",names.get(a)+"|"+names.get(b));
                } else if (!intersections.isEmpty()) fail(Problem.INTERSECTION,"route_intersection",names.get(a)+"|"+names.get(b),0,0,intersections.size(),0,"layout");
            }
        }
        var solution = NativeCrossoverParts.build(kind,geometry,paths,origin,gauge,complete);
        var fixedParts = solution.parts(); var fixedRails = solution.rails(); var switches = solution.switches();
        List<Sleeper> sleepers;
        try (var stage = NativeLayoutProfile.stage(kind.label()+"/sleepers")) {
            sleepers = complete ? NativeCrossoverSleepers.build(paths,solution.zones(),scale) : List.of();
            if (stage != null) stage.value("sleepers", sleepers.size());
        }
        AABB bounds = null;
        for (var path : paths) for (var n : path.nodes()) {
            var p = add(origin,n.point(),1); var box = new AABB(p.x()-3.4*scale,origin.y()-1.41*scale,p.z()-3.4*scale,p.x()+3.4*scale,origin.y()+0.01*scale,p.z()+3.4*scale);
            bounds = bounds == null ? box : bounds.minmax(box);
        }
        var chunks = new LinkedHashSet<Long>();
        for (var path : paths) for (var node : path.nodes()) {
            var p = add(origin,node.point(),1); double radius = 3.4*scale;
            for (int x = (int)Math.floor((p.x()-radius)/16); x <= (int)Math.floor((p.x()+radius)/16); x++)
                for (int z = (int)Math.floor((p.z()-radius)/16); z <= (int)Math.floor((p.z()+radius)/16); z++) chunks.add(ChunkPos.asLong(x,z));
        }
        for (var part : fixedParts) {
            var footprint = NativeCrossoverParts.footprint(part,scale);
            double minX = footprint.stream().mapToDouble(TrackVector::x).min().orElseThrow()+origin.x();
            double maxX = footprint.stream().mapToDouble(TrackVector::x).max().orElseThrow()+origin.x();
            double minZ = footprint.stream().mapToDouble(TrackVector::z).min().orElseThrow()+origin.z();
            double maxZ = footprint.stream().mapToDouble(TrackVector::z).max().orElseThrow()+origin.z();
            double height = origin.y()+part.position().y();
            bounds = bounds.minmax(new AABB(minX,height-1.41*scale,minZ,maxX,height+.01*scale,maxZ));
            for (int x = (int)Math.floor(minX/16); x <= (int)Math.floor(maxX/16); x++)
                for (int z = (int)Math.floor(minZ/16); z <= (int)Math.floor(maxZ/16); z++) chunks.add(ChunkPos.asLong(x,z));
        }
        return new NativeCrossoverLayout(kind,origin,scale,paths,fixedRails,fixedParts,solution.connections(),sleepers,switches,chunks,bounds,paths.stream().mapToInt(p -> p.nodes().size()).sum());
    }
    static double[] referenceRange(List<Path> paths) {
        var reference = paths.getFirst(); double first = 0, last = reference.length();
        for (var path : paths) for (double end : new double[]{0,path.length()}) {
            var point = path.at(end).point();
            var nearest = reference.nodes().stream().min(Comparator.comparingDouble(n -> dot(add(n.point(),point,-1),add(n.point(),point,-1)))).orElseThrow();
            double station = station(reference,point,nearest.tangent()); var frame = reference.at(station);
            double projected = station+dot(add(point,frame.point(),-1),frame.tangent());
            first = Math.min(first,projected); last = Math.max(last,projected);
        }
        return new double[]{first,last};
    }
    private static Node sample(TrackGeometry curve, double s, TrackVector origin, double gauge) {
        var p = curve.sample(s);
        if (Math.abs(p.position().y()-origin.y()) > 1e-6 || Math.abs(p.tangent().y()) > 1e-6 || Math.abs(p.gauge()-gauge) > 1e-6) throw new InvalidLayout(Problem.SPACE);
        return new Node(s,add(p.position(),origin,-1),p.tangent());
    }
    private static void subdivide(TrackGeometry curve, Node a, Node b, TrackVector origin, double gauge, double scale, List<Node> output) {
        var mid = sample(curve,(a.station()+b.station())/2,origin,gauge);
        var d = add(mid.point(),multiply(add(a.point(),b.point(),1),0.5),-1);
        if (dot(d,d) > Math.pow(0.0001*scale,2) && b.station()-a.station() > 0.001*scale) {
            subdivide(curve,a,mid,origin,gauge,scale,output); subdivide(curve,mid,b,origin,gauge,scale,output);
        } else output.add(b);
    }
    static double station(Path path, TrackVector origin, TrackVector tangent) {
        double lo = 0, hi = path.length();
        while (hi-lo > 1e-7) { double mid = (lo+hi)/2; if (dot(add(path.at(mid).point(),origin,-1),tangent) < 0) lo = mid; else hi = mid; }
        return (lo+hi)/2;
    }
    private static List<Hit> hits(Path a, Path b, double ao, double bo, boolean self) {
        var result = new ArrayList<Hit>();
        for (int i = 1; i < a.nodes().size(); i++) {
            var p = a.nodes().get(i-1); var q = a.nodes().get(i); var pa = p.edge(ao); var qa = q.edge(ao);
            double ax = qa.x()-pa.x(), az = qa.z()-pa.z();
            for (int j = self ? i+2 : 1; j < b.nodes().size(); j++) {
                var r = b.nodes().get(j-1); var s = b.nodes().get(j); var rb = r.edge(bo); var sb = s.edge(bo);
                if (Math.max(pa.x(),qa.x()) < Math.min(rb.x(),sb.x())-1e-8 || Math.min(pa.x(),qa.x()) > Math.max(rb.x(),sb.x())+1e-8
                        || Math.max(pa.z(),qa.z()) < Math.min(rb.z(),sb.z())-1e-8 || Math.min(pa.z(),qa.z()) > Math.max(rb.z(),sb.z())+1e-8) continue;
                double bx = sb.x()-rb.x(), bz = sb.z()-rb.z(), det = ax*bz-az*bx;
                if (Math.abs(det) < 1e-14) continue;
                double dx = rb.x()-pa.x(), dz = rb.z()-pa.z(), t = (dx*bz-dz*bx)/det, u = (dx*az-dz*ax)/det;
                if (t < -1e-9 || t > 1+1e-9 || u < -1e-9 || u > 1+1e-9) continue;
                double sa = p.station()+(q.station()-p.station())*t, bs = r.station()+(s.station()-r.station())*u;
                if (!self && (sa < 1e-4 || sa > a.length()-1e-4) && (bs < 1e-4 || bs > b.length()-1e-4)) continue;
                var point = new TrackVector(pa.x()+t*ax,0,pa.z()+t*az);
                if (result.stream().anyMatch(hit -> distance(hit.point(),point) < 1e-4)) continue;
                double angle = Math.toDegrees(Math.acos(Math.clamp(Math.abs(ax*bx+az*bz)/Math.sqrt((ax*ax+az*az)*(bx*bx+bz*bz)),0,1)));
                result.add(new Hit(sa,bs,point,angle));
            }
        }
        return result;
    }
    private static void angle(Hit hit, String control, String path) {
        if (hit.angle() < 0.5 || hit.angle() > 45) fail(Problem.ANGLE,"crossing_angle",path,0,0,hit.angle(),0.5,control);
    }
    private static double distance(TrackVector a, TrackVector b) { var d = add(a,b,-1); return Math.sqrt(dot(d,d)); }
    private static void fail(Problem p, String stage, String path, int side, double from, double to, double required, String control) {
        throw new InvalidLayout(p,NativeLayoutDiagnostic.at(stage,path,side,from,to,required).in(control,path));
    }
    public static NativeCrossoverLayout example() { return example(Kind.SCISSORS); }
    public static NativeCrossoverLayout example(Kind kind) {
        var names = kind.paths;
        var paths = new LinkedHashMap<String,List<TrackGeometry>>();
        double[][][] points = {{{0,0},{0,32},{0,64},{0,96}},{{12,0},{12,32},{12,64},{12,96}},{{0,0},{0,32},{12,64},{12,96}},{{12,0},{12,32},{0,64},{0,96}}};
        for (int i = 0; i < names.size(); i++) { var p = points[i]; paths.put(names.get(i),List.of(new BezierTrackGeometry(
                new TrackPoint(p[0][0],p[0][1]),new TrackPoint(p[1][0],p[1][1]),new TrackPoint(p[2][0],p[2][1]),new TrackPoint(p[3][0],p[3][1]),new LinearGrade(0,0),1.435))); }
        return build(new TrackAssemblyDefinition.Geometry(Map.of(),paths),1.435);
    }
}
