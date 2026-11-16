/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Centre-out crossing layout with rigid core components and ordinary outward-facing path sections. */
public record NativeDiamondLayout(TrackVector origin, double scale, List<Part> parts,
                                  List<OrdinaryRun> ordinary, Set<Long> chunks, AABB bounds, int samples) {
    public static final double RAIL_X = 0.74875;
    public static final double ORDINARY_PERIOD = 1.5;
    private static final double SLEEPER_HALF_WIDTH = 1.239434, SLEEPER_HALF_LENGTH = 0.820367;
    private static final double WING_X = 0.0311652;
    private static final double RAIL_START = -0.8101502, RAIL_END = 0.80895454;
    private static final double JOINT_OVERLAP = 0.005;
    private static final double NOSE_X = 0.03129997, NOSE_END = 1.9996259;

    public NativeDiamondLayout {
        parts = List.copyOf(parts); ordinary = List.copyOf(ordinary); chunks = Set.copyOf(chunks);
    }
    /** A path section directed from the crossing towards its outer endpoint. */
    public record OrdinaryRun(TrackGeometry geometry, int modules) {}
    public int partCount() { return parts.size()+ordinary.stream().mapToInt(OrdinaryRun::modules).sum(); }
    public enum Problem { INTERSECTION, ANGLE, SPACE }
    public static final class InvalidLayout extends IllegalArgumentException {
        private final Problem problem;
        public InvalidLayout(Problem problem) { super(problem.name()); this.problem = problem; }
        public Problem problem() { return problem; }
    }
    public record Part(String name, TrackVector position, double yaw, double localX, double localZ, long chunk) {
        public Matrix4f transform(TrackVector anchor, double scale) {
            return new Matrix4f().translation((float)(position.x()-anchor.x()), (float)(position.y()-anchor.y()),
                    (float)(position.z()-anchor.z())).rotateY((float)yaw)
                    .translate((float)(-localX*scale), 0, (float)(-localZ*scale)).scale((float)scale);
        }
    }
    private record Edge(double distance, TrackVector point) {}
    private record Crossing(double first, double second, TrackVector point) {}
    private record RailEnd(int side, double distance, TrackVector point, double yaw, double overlap) {}
    private record SleeperRow(double second, TrackVector a, TrackVector b, double yaw, double across) {}
    private record SleeperBoundary(double distance, double yaw) {}
    private record Footprint(List<TrackVector> hull, double minX, double minZ, double maxX, double maxZ) {
        boolean intersects(Footprint other) {
            return maxX > other.minX && other.maxX > minX && maxZ > other.minZ && other.maxZ > minZ
                    && !separated(hull, other.hull) && !separated(other.hull, hull);
        }
    }
    private static final class Path {
        final TrackGeometry geometry;
        final Map<Long, TrackPose> poses = new HashMap<>();
        final Map<Long, List<Edge>> edges = new HashMap<>();
        final List<RailEnd> railEnds = new ArrayList<>();
        final List<Footprint> modules = new ArrayList<>();
        double coreStart, coreEnd, sleeperStart, sleeperEnd, firstJoint, lastJoint;
        Path(TrackGeometry geometry) { this.geometry = geometry; coreEnd = sleeperEnd = geometry.getLength(); }
        double length() { return geometry.getLength(); }
        TrackPose at(double s) {
            double distance = Math.clamp(s, 0, length());
            return poses.computeIfAbsent(Double.doubleToLongBits(distance), ignored -> geometry.sample(distance));
        }
        TrackVector edge(double s, double offset) {
            var pose = at(s);
            return add(pose.position(), pose.lateral(), offset);
        }
    }

    public static NativeDiamondLayout build(TrackAssemblyDefinition.Geometry geometry, double gauge) {
        var first = geometry.paths().get("line_one");
        var second = geometry.paths().get("line_two");
        if (first == null || second == null || first.size() != 1 || second.size() != 1
                || !Double.isFinite(gauge) || gauge <= 0) throw new InvalidLayout(Problem.INTERSECTION);
        return build(first.getFirst(), second.getFirst(), gauge);
    }

    public static NativeDiamondLayout example() {
        double length = 16, angle = Math.toRadians(20);
        return build(new StraightTrackGeometry(new TrackPoint(0, 0), new TrackPoint(0, 2*length), new LinearGrade(0, 0), 1.435),
                new StraightTrackGeometry(new TrackPoint(-Math.sin(angle)*length, length-Math.cos(angle)*length),
                        new TrackPoint(Math.sin(angle)*length, length+Math.cos(angle)*length), new LinearGrade(0, 0), 1.435), 1.435);
    }

    private static NativeDiamondLayout build(TrackGeometry first, TrackGeometry second, double gauge) {
        var a = new Path(first); var b = new Path(second);
        double scale = gauge/1.435;
        double height = a.at(0).position().y();
        for (var path : List.of(a, b)) for (double s : new double[]{0, path.length()/2, path.length()}) {
            var pose = path.at(s);
            if (Math.abs(pose.position().y()-height) > 1e-6 || Math.abs(pose.tangent().y()) > 1e-6
                    || Math.abs(pose.gauge()-gauge) > 1e-6) throw new InvalidLayout(Problem.SPACE);
        }
        var center = crossing(a, b, 0, 0, scale);
        angle(a.at(center.first()), b.at(center.second()));
        int reversedSamples = 0;
        if (dot(a.at(center.first()).tangent(), b.at(center.second()).tangent()) < 0) {
            reversedSamples = b.poses.size(); b = new Path(second.reversed());
            center = crossing(a, b, 0, 0, scale);
        }
        var parts = new ArrayList<Part>();
        for (int sa : new int[]{-1, 1}) for (int sb : new int[]{-1, 1}) {
            // The ordinary turnout locates the frog on half-gauge reference lines, not rail centers.
            double offset = sa != sb ? gauge/2 : RAIL_X*scale;
            var joint = crossing(a, b, sa*offset, sb*offset, scale);
            var pa = a.at(joint.first()); var pb = b.at(joint.second());
            double degrees = angle(pa, pb);
            if (dot(pa.tangent(), pb.tangent()) <= 0 || 1-sa*RAIL_X*scale*pa.curvature() <= 0
                    || 1-sb*RAIL_X*scale*pb.curvature() <= 0) throw new InvalidLayout(Problem.SPACE);
            requireSpace(a, joint.first(), 3.5*scale);
            requireSpace(b, joint.second(), 3.5*scale);
            if (sa != sb) acute(parts, a, b, joint, center, sa, sb, degrees, scale);
            else {
                // Keep the requested fixed overlap by translating complete source modules.
                double overlap = JOINT_OVERLAP*scale;
                a.railEnds.add(new RailEnd(sa, joint.first(), joint.point(), yaw(pa), overlap));
                b.railEnds.add(new RailEnd(sb, joint.second(), joint.point(), yaw(pb), overlap));
            }
        }
        var core = parts.stream().map(part -> coreFootprint(part, scale)).toList();
        moduleFootprints(a, scale); moduleFootprints(b, scale);
        var ordinary = new ArrayList<OrdinaryRun>();
        ordinaryRuns(a, b, center, core, scale, ordinary);
        fixedTrack(parts, a, scale); fixedTrack(parts, b, scale);
        sleepers(parts, a, b, center, scale);
        var chunks = new LinkedHashSet<Long>();
        double radius = 4.5*scale;
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (var part : parts) {
            chunks.add(part.chunk());
            minX = Math.min(minX, part.position().x()-radius); maxX = Math.max(maxX, part.position().x()+radius);
            minZ = Math.min(minZ, part.position().z()-radius); maxZ = Math.max(maxZ, part.position().z()+radius);
        }
        for (var run : ordinary) for (int index = 0; index < run.modules(); index++) {
            double start = index*ORDINARY_PERIOD*scale;
            double end = Math.min(run.geometry().getLength(), start+ORDINARY_PERIOD*scale);
            var point = run.geometry().sample(start+(end-start)/2).position();
            chunks.add(chunk(point));
            minX = Math.min(minX, point.x()-radius); maxX = Math.max(maxX, point.x()+radius);
            minZ = Math.min(minZ, point.z()-radius); maxZ = Math.max(maxZ, point.z()+radius);
        }
        return new NativeDiamondLayout(a.at(0).position(), scale, parts, ordinary, chunks,
                new AABB(minX, height-1.5*scale, minZ, maxX, height+0.25*scale, maxZ), a.poses.size()+b.poses.size()+reversedSamples);
    }

    private static void moduleFootprints(Path path, double scale) {
        path.firstJoint = path.railEnds.stream().mapToDouble(RailEnd::distance).min().orElseThrow();
        path.lastJoint = path.railEnds.stream().mapToDouble(RailEnd::distance).max().orElseThrow();
        double period = ORDINARY_PERIOD*scale;
        int count = (int)Math.ceil(path.length()/period);
        // Continuous conservative coverage of the other path, independent of the drawing phase.
        for (int index = 0; index < count; index++) path.modules.add(moduleFootprint(path, (index+0.5)*period, scale));
    }

    private static Footprint moduleFootprint(Path path, double center, double scale) {
        // Match the existing flattening tolerance conservatively at the outer sleeper edges.
        var left = flatten(path, -(SLEEPER_HALF_WIDTH+0.0005)*scale, scale);
        var right = flatten(path, (SLEEPER_HALF_WIDTH+0.0005)*scale, scale);
        double start = center-SLEEPER_HALF_LENGTH*scale, end = center+SLEEPER_HALF_LENGTH*scale;
        var points = new ArrayList<TrackVector>();
        for (int side : new int[]{-1, 1}) {
            double offset = side*(SLEEPER_HALF_WIDTH+0.0005)*scale;
            points.add(extendedEdge(path, start, offset)); points.add(extendedEdge(path, end, offset));
            var edges = side < 0 ? left : right;
            int low = 0, high = edges.size();
            while (low < high) {
                int mid = (low+high)/2;
                if (edges.get(mid).distance() <= start) low = mid+1; else high = mid;
            }
            for (int i = low; i < edges.size() && edges.get(i).distance() < end; i++) {
                points.add(edges.get(i).point());
            }
        }
        return footprint(points);
    }

    private static TrackVector extendedEdge(Path path, double s, double offset) {
        var pose = path.at(s);
        return add(add(pose.position(), pose.lateral(), offset), pose.tangent(), s-Math.clamp(s, 0, path.length()));
    }

    private static void ordinaryRuns(Path a, Path b, Crossing center, List<Footprint> core, double scale, List<OrdinaryRun> runs) {
        for (int direction : new int[]{-1, 1}) {
            boolean onA = false, onB = false;
            SleeperBoundary previousA = null, previousB = null;
            for (int index = 0; ; index++) {
                double s = center.first()+direction*index*scale;
                if (s < 0 || s > a.length()) break;
                var row = sleeperRow(a, b, s, scale);
                double heading = row == null ? yaw(a.at(s)) : row.yaw();
                var currentA = new SleeperBoundary(s, heading);
                var currentB = row == null ? null : new SleeperBoundary(row.second(), heading);
                if (!onA && previousA != null) onA = ordinaryRun(a, b, currentA, previousA, direction, core, scale, runs);
                if (!onB && currentB != null && previousB != null)
                    onB = ordinaryRun(b, a, currentB, previousB, direction, core, scale, runs);
                if (onA && onB) break;
                previousA = currentA; previousB = currentB;
            }
            // Unequal endpoints can leave an outer part of the second path without a paired row.
            if (!onB) {
                SleeperBoundary previous = null;
                for (int index = 0; ; index++) {
                    double s = center.second()+direction*index*scale;
                    if (s < 0 || s > b.length()) break;
                    if (sleeperRow(b, a, s, scale) != null) { previous = null; continue; }
                    var current = new SleeperBoundary(s, yaw(b.at(s)));
                    if (previous != null && ordinaryRun(b, a, current, previous, direction, core, scale, runs)) break;
                    previous = current;
                }
            }
        }
    }

    private static double ordinaryStart(Path path, SleeperBoundary row, int direction, double scale) {
        double edge = row.distance()+direction*0.25*scale*Math.cos(row.yaw()-yaw(path.at(row.distance())));
        return edge+direction*0.25*scale;
    }

    private static boolean ordinaryRun(Path path, Path other, SleeperBoundary candidate, SleeperBoundary retained, int direction,
                                        List<Footprint> core, double scale, List<OrdinaryRun> runs) {
        double period = ORDINARY_PERIOD*scale;
        double candidateCenter = ordinaryStart(path, candidate, direction, scale)+direction*period/2;
        if (!separate(moduleFootprint(path, candidateCenter, scale), other.modules, core)) return false;
        // Replace the outermost special group: continue directly from the preceding retained row.
        double start = ordinaryStart(path, retained, direction, scale);
        double length = direction < 0 ? start : path.length()-start;
        if (length <= 0) return false;
        double firstCenter = start+direction*period/2;
        if (direction < 0 ? firstCenter+SLEEPER_HALF_LENGTH*scale > path.firstJoint
                : firstCenter-SLEEPER_HALF_LENGTH*scale < path.lastJoint) return false;
        int count = (int)Math.ceil(length/period);
        for (int index = 0; index < count; index++) {
            // The replacement module may overlap the other path locally at this transition.
            // Core components remain protected; every subsequent module must be fully separated.
            if (!separate(moduleFootprint(path, firstCenter+direction*index*period, scale),
                    index == 0 ? List.of() : other.modules, core)) return false;
        }
        var geometry = direction < 0 ? path.geometry.section(0, start).reversed() : path.geometry.section(start, path.length());
        runs.add(new OrdinaryRun(geometry, count));
        if (direction < 0) {
            path.sleeperStart = retained.distance();
            path.coreStart = firstCenter-RAIL_START*scale;
        } else {
            path.sleeperEnd = retained.distance();
            path.coreEnd = firstCenter+RAIL_START*scale;
        }
        return true;
    }

    private static boolean separate(Footprint module, List<Footprint> other, List<Footprint> core) {
        for (var footprint : other) if (module.intersects(footprint)) return false;
        for (var footprint : core) if (module.intersects(footprint)) return false;
        return true;
    }

    private static Footprint coreFootprint(Part part, double scale) {
        // Reference bounds of the existing complete r11 components, including guard fasteners.
        double minX, maxX, minZ, maxZ;
        if (part.name().startsWith("nose")) {
            minX = -0.106092; maxX = 0.106092; minZ = 0; maxZ = NOSE_END;
        } else if (part.name().startsWith("guard")) {
            minX = 1.10517; maxX = 1.360414; minZ = -1.193223; maxZ = 2.106004;
            if (part.name().endsWith("R")) { minX = -1.360414; maxX = -1.10517; }
        } else if (part.name().startsWith("wingFront")) {
            minX = -0.049827; maxX = 0.127347; minZ = -0.000001; maxZ = 2.099801;
            if (part.name().endsWith("R")) { minX = -0.127347; maxX = 0.049827; }
        } else {
            minX = -0.043492; maxX = 0.106092; minZ = -1.000215; maxZ = 0;
            if (part.name().equals("wingRr")) { minX = -0.106092; maxX = 0.043492; }
        }
        var points = new ArrayList<TrackVector>();
        for (double x : new double[]{minX, maxX}) for (double z : new double[]{minZ, maxZ}) {
            points.add(shifted(part.position(), part.yaw(), (x-part.localX())*scale, (z-part.localZ())*scale));
        }
        return footprint(points);
    }

    private static Footprint footprint(List<TrackVector> points) {
        points.sort(Comparator.comparingDouble(TrackVector::x).thenComparingDouble(TrackVector::z));
        var hull = new ArrayList<TrackVector>();
        for (var point : points) {
            while (hull.size() > 1 && cross(hull.get(hull.size()-2), hull.getLast(), point) <= 0) hull.removeLast();
            hull.add(point);
        }
        int lower = hull.size();
        for (int i = points.size()-2; i >= 0; i--) {
            var point = points.get(i);
            while (hull.size() > lower && cross(hull.get(hull.size()-2), hull.getLast(), point) <= 0) hull.removeLast();
            hull.add(point);
        }
        hull.removeLast();
        return new Footprint(List.copyOf(hull), points.getFirst().x(), points.stream().mapToDouble(TrackVector::z).min().orElseThrow(),
                points.getLast().x(), points.stream().mapToDouble(TrackVector::z).max().orElseThrow());
    }

    private static double cross(TrackVector a, TrackVector b, TrackVector c) {
        return (b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x());
    }

    private static boolean separated(List<TrackVector> axes, List<TrackVector> other) {
        for (int i = 0; i < axes.size(); i++) {
            var a = axes.get(i); var b = axes.get((i+1)%axes.size());
            double x = b.z()-a.z(), z = a.x()-b.x();
            double lowA = Double.POSITIVE_INFINITY, highA = Double.NEGATIVE_INFINITY;
            double lowB = lowA, highB = highA;
            for (var p : axes) { double d = p.x()*x+p.z()*z; lowA = Math.min(lowA, d); highA = Math.max(highA, d); }
            for (var p : other) { double d = p.x()*x+p.z()*z; lowB = Math.min(lowB, d); highB = Math.max(highB, d); }
            if (highA <= lowB || highB <= lowA) return true;
        }
        return false;
    }

    private static void acute(List<Part> parts, Path a, Path b, Crossing joint, Crossing center,
                              int sa, int sb, double degrees, double scale) {
        // The two acute ends point away from the center of the diamond, independent of port order.
        double direction = joint.first() > center.first() ? 1 : -1;
        double baseA = yaw(a.at(joint.first())) + (direction < 0 ? Math.PI : 0);
        double baseB = yaw(b.at(joint.second())) + (direction < 0 ? Math.PI : 0);
        double ya = direction(a, joint.first(), joint.first()+direction*2*scale);
        double yb = direction(b, joint.second(), joint.second()+direction*2*scale);
        double noseAngle = Math.abs(Math.toDegrees(Math.atan2(Math.sin(yb-ya), Math.cos(yb-ya))));
        if (noseAngle < 0.5 || noseAngle > 45) throw new InvalidLayout(Problem.ANGLE);
        String variant = noseAngle < 5 ? "2_5" : noseAngle < 10 ? "5" : noseAngle < 20 ? "10" : "20";
        boolean right = Math.sin(yb-ya) > 0;
        addPart(parts, (right ? "noseR" : "noseL")+variant, joint.point(), ya, 0, 0);
        addPart(parts, (right ? "noseL" : "noseR")+variant, joint.point(), yb, 0, 0);
        a.railEnds.add(new RailEnd(sa, joint.first()+direction*NOSE_END*scale,
                shifted(joint.point(), ya, (right ? 1 : -1)*NOSE_X*scale, NOSE_END*scale), ya-(direction < 0 ? Math.PI : 0), 0));
        b.railEnds.add(new RailEnd(sb, joint.second()+direction*NOSE_END*scale,
                shifted(joint.point(), yb, (right ? -1 : 1)*NOSE_X*scale, NOSE_END*scale), yb-(direction < 0 ? Math.PI : 0), 0));
        double gap = 0.0625*scale/Math.sin(Math.toRadians(degrees));
        requireSpace(a, joint.first(), 3.5*scale+gap);
        requireSpace(b, joint.second(), 3.5*scale+gap);
        // As on the ordinary turnout, each rear follows the opposite path into the shared joint.
        wing(parts, right ? "R" : "L", shifted(joint.point(), baseB, 0, -gap), ya,
                direction(b, joint.second()-direction*scale, joint.second()), scale);
        wing(parts, right ? "L" : "R", shifted(joint.point(), baseA, 0, -gap), yb,
                direction(a, joint.first()-direction*scale, joint.first()), scale);
    }

    private static double direction(Path path, double from, double to) {
        var start = path.at(from).position();
        var end = path.at(to).position();
        return Math.atan2(end.x()-start.x(), end.z()-start.z());
    }

    private static void wing(List<Part> parts, String side, TrackVector origin, double front, double rear, double scale) {
        double x = (side.equals("L") ? 1 : -1)*WING_X;
        // Extraction preserves source coordinates: both pieces retain the ordinary turnout's front frame.
        addPart(parts, "wingFront"+side, origin, front, 0, 0);
        addPart(parts, "guard"+side, origin, front, 0, 0);
        var joint = shifted(origin, front, x*scale, 0);
        addPart(parts, "wing"+side+"r", shifted(joint, rear, 0, JOINT_OVERLAP*scale), rear, x, 0);
    }
    private static void sleepers(List<Part> parts, Path a, Path b, Crossing center, double scale) {
        int first = (int)Math.ceil(-center.first()/scale), last = (int)Math.floor((a.length()-center.first())/scale);
        for (int index = first; index <= last; index++) {
            double s = center.first()+index*scale;
            var row = sleeperRow(a, b, s, scale);
            if (row == null) { unpairedSleeper(parts, a, b, s, scale); continue; }
            boolean onA = coreSleeper(a, s), onB = coreSleeper(b, row.second());
            if (!onA && !onB) continue;
            var pa = a.at(s); var pb = b.at(row.second());
            if (dot(pa.tangent(), pb.tangent()) < 0.5
                    || Math.hypot(row.b().x()-pb.position().x(), row.b().z()-pb.position().z()) > 0.00001*scale)
                throw new InvalidLayout(Problem.SPACE);
            // Coincident rows use the stable path order; both complementary sources remain present.
            boolean right = row.across() >= -0.00001*scale;
            if (onA) addPart(parts, right ? "sleeper_point02R" : "sleeper_point02L", row.a(), row.yaw(), 0, 0.25);
            if (onB) addPart(parts, right ? "sleeper_point02L" : "sleeper_point02R", row.b(), row.yaw(), 0, 0.25);
        }
        double otherCenter = center.second();
        first = (int)Math.ceil(-otherCenter/scale); last = (int)Math.floor((b.length()-otherCenter)/scale);
        for (int index = first; index <= last; index++) {
            double s = otherCenter+index*scale;
            if (coreSleeper(b, s) && sleeperRow(b, a, s, scale) == null) unpairedSleeper(parts, b, a, s, scale);
        }
    }
    private static boolean coreSleeper(Path path, double s) { return s >= path.sleeperStart && s <= path.sleeperEnd; }
    private static void unpairedSleeper(List<Part> parts, Path path, Path other, double s, double scale) {
        if (!coreSleeper(path, s)) return;
        var pose = path.at(s);
        var start = other.at(0).position(); var end = other.at(other.length()).position();
        var nearest = Math.hypot(start.x()-pose.position().x(), start.z()-pose.position().z())
                < Math.hypot(end.x()-pose.position().x(), end.z()-pose.position().z()) ? start : end;
        double across = (nearest.x()-pose.position().x())*pose.lateral().x()+(nearest.z()-pose.position().z())*pose.lateral().z();
        addPart(parts, across >= -0.00001*scale ? "sleeper_point02R" : "sleeper_point02L", pose.position(), yaw(pose), 0, 0.25);
    }
    private static SleeperRow sleeperRow(Path a, Path b, double s, double scale) {
        double tolerance = 0.00001*scale;
        var pa = a.at(s);
        if (sleeperProjection(pa, b.at(0)) > tolerance || sleeperProjection(pa, b.at(b.length())) < -tolerance)
            return null;
        double low = 0, high = b.length();
        for (int iteration = 0; iteration < 40; iteration++) {
            double mid = (low+high)/2;
            if (sleeperProjection(pa, b.at(mid)) < 0) low = mid; else high = mid;
        }
        double other = (low+high)/2; var pb = b.at(other);
        double delta = yaw(pb)-yaw(pa);
        double heading = yaw(pa)+Math.atan2(Math.sin(delta), Math.cos(delta))/2;
        double across = (pb.position().x()-pa.position().x())*Math.cos(heading)
                -(pb.position().z()-pa.position().z())*Math.sin(heading);
        var paired = shifted(pa.position(), heading, across, 0);
        return new SleeperRow(other, pa.position(), paired, heading, across);
    }
    private static double sleeperProjection(TrackPose a, TrackPose b) {
        return (b.position().x()-a.position().x())*(a.tangent().x()+b.tangent().x())
                +(b.position().z()-a.position().z())*(a.tangent().z()+b.tangent().z());
    }
    private static void fixedTrack(List<Part> parts, Path path, double scale) {
        for (int side : new int[]{-1, 1}) {
            var ends = path.railEnds.stream().filter(end -> end.side() == side)
                    .sorted(Comparator.comparingDouble(RailEnd::distance)).toList();
            railApproach(parts, path, ends.getFirst(), true, scale);
            railApproach(parts, path, ends.getLast(), false, scale);
        }
        int row = 0;
        for (double s = 0; s < path.length(); s += 2*scale) {
            var pose = path.at(s);
            addPart(parts, "ballast0"+(row++%4+1), pose.position(), yaw(pose), 0, 0);
        }
    }
    private static void railApproach(List<Part> parts, Path path, RailEnd end, boolean fromStart, double scale) {
        double direction = fromStart ? -1 : 1;
        double length = (RAIL_END-RAIL_START)*scale;
        double s = end.distance()-direction*end.overlap(), heading = end.yaw();
        var anchor = shifted(end.point(), heading, 0, -direction*end.overlap());
        // Pin the original rail end section to the joint, then repeat complete rigid modules outward.
        // Each next module starts at the preceding physical end. The last may overhang the path.
        while (fromStart ? s > path.coreStart : s < path.coreEnd) {
            addPart(parts, end.side() > 0 ? "railL" : "railR", anchor, heading,
                    end.side()*RAIL_X, fromStart ? RAIL_END : RAIL_START);
            anchor = shifted(anchor, heading, 0, direction*length);
            s += direction*length;
            var next = path.edge(s+direction*length, end.side()*RAIL_X*scale);
            heading = Math.atan2(direction*(next.x()-anchor.x()), direction*(next.z()-anchor.z()));
        }
    }
    private static void requireSpace(Path path, double s, double reach) {
        if (s < reach || path.length()-s < reach) throw new InvalidLayout(Problem.SPACE);
    }
    private static double angle(TrackPose a, TrackPose b) {
        double degrees = Math.toDegrees(Math.acos(Math.clamp(Math.abs(dot(a.tangent(), b.tangent())), 0, 1)));
        if (degrees+1e-7 < 0.5 || degrees > 45+1e-7) throw new InvalidLayout(Problem.ANGLE);
        return degrees;
    }
    private static void addPart(List<Part> parts, String name, TrackVector point, double yaw, double x, double z) {
        parts.add(new Part(name, point, yaw, x, z, chunk(point)));
    }
    private static long chunk(TrackVector point) { return ChunkPos.asLong((int)Math.floor(point.x()/16), (int)Math.floor(point.z()/16)); }

    private static Crossing crossing(Path a, Path b, double offsetA, double offsetB, double scale) {
        var first = flatten(a, offsetA, scale); var second = flatten(b, offsetB, scale);
        Crossing result = null;
        for (int i = 1; i < first.size(); i++) for (int j = 1; j < second.size(); j++) {
            var p = first.get(i-1); var pe = first.get(i); var q = second.get(j-1); var qe = second.get(j);
            if (Math.max(p.point().x(), pe.point().x()) < Math.min(q.point().x(), qe.point().x())
                    || Math.max(q.point().x(), qe.point().x()) < Math.min(p.point().x(), pe.point().x())
                    || Math.max(p.point().z(), pe.point().z()) < Math.min(q.point().z(), qe.point().z())
                    || Math.max(q.point().z(), qe.point().z()) < Math.min(p.point().z(), pe.point().z())) continue;
            double rx = pe.point().x()-p.point().x(), rz = pe.point().z()-p.point().z();
            double sx = qe.point().x()-q.point().x(), sz = qe.point().z()-q.point().z();
            double denominator = rx*sz-rz*sx;
            if (Math.abs(denominator) < 1e-12) continue;
            double dx = q.point().x()-p.point().x(), dz = q.point().z()-p.point().z();
            double t = (dx*sz-dz*sx)/denominator, u = (dx*rz-dz*rx)/denominator;
            if (t < -1e-9 || t > 1+1e-9 || u < -1e-9 || u > 1+1e-9) continue;
            double sa = p.distance()+t*(pe.distance()-p.distance());
            double sb = q.distance()+u*(qe.distance()-q.distance());
            var candidate = refine(a, b, sa, sb, offsetA, offsetB, scale);
            if (candidate.first() <= 1e-5*scale || candidate.first() >= a.length()-1e-5*scale
                    || candidate.second() <= 1e-5*scale || candidate.second() >= b.length()-1e-5*scale) continue;
            if (result != null && (Math.abs(result.first()-candidate.first()) > 0.01*scale
                    || Math.abs(result.second()-candidate.second()) > 0.01*scale)) throw new InvalidLayout(Problem.INTERSECTION);
            result = candidate;
        }
        if (result == null) throw new InvalidLayout(Problem.INTERSECTION);
        return result;
    }
    private static Crossing refine(Path a, Path b, double sa, double sb, double oa, double ob, double scale) {
        for (int i = 0; i < 12; i++) {
            var p = a.edge(sa, oa); var q = b.edge(sb, ob);
            double dx = q.x()-p.x(), dz = q.z()-p.z();
            if (Math.hypot(dx, dz) <= 0.00001*scale) break;
            var ta = a.at(sa); var tb = b.at(sb);
            double fa = 1-oa*ta.curvature(), fb = 1-ob*tb.curvature();
            double ax = ta.tangent().x()*fa, az = ta.tangent().z()*fa;
            double bx = tb.tangent().x()*fb, bz = tb.tangent().z()*fb;
            double determinant = ax*bz-az*bx;
            if (Math.abs(determinant) < 1e-12) throw new InvalidLayout(Problem.ANGLE);
            sa = Math.clamp(sa+(dx*bz-dz*bx)/determinant, 0, a.length());
            sb = Math.clamp(sb+(dx*az-dz*ax)/determinant, 0, b.length());
        }
        var p = a.edge(sa, oa); var q = b.edge(sb, ob);
        if (Math.hypot(p.x()-q.x(), p.z()-q.z()) > 0.001*scale) throw new InvalidLayout(Problem.INTERSECTION);
        return new Crossing(sa, sb, add(p, add(q, p, -1), 0.5));
    }
    private static List<Edge> flatten(Path path, double offset, double scale) {
        long key = Double.doubleToLongBits(offset);
        var cached = path.edges.get(key);
        if (cached != null) return cached;
        var result = new ArrayList<Edge>();
        var first = new Edge(0, path.edge(0, offset)); result.add(first);
        flatten(path, offset, scale, first, new Edge(path.length(), path.edge(path.length(), offset)), 0, result);
        path.edges.put(key, result);
        return result;
    }
    private static void flatten(Path path, double offset, double scale, Edge a, Edge b, int depth, List<Edge> result) {
        double middle = (a.distance()+b.distance())/2;
        var mid = new Edge(middle, path.edge(middle, offset));
        boolean split = b.distance()-a.distance() > 2*scale;
        for (int i = 1; i < 4; i++) {
            double t = i/4.0;
            var actual = i == 2 ? mid.point() : path.edge(a.distance()+(b.distance()-a.distance())*t, offset);
            double x = a.point().x()+(b.point().x()-a.point().x())*t;
            double z = a.point().z()+(b.point().z()-a.point().z())*t;
            split |= Math.hypot(actual.x()-x, actual.z()-z) > 0.0005*scale;
        }
        if (split && depth < 18) {
            flatten(path, offset, scale, a, mid, depth+1, result);
            flatten(path, offset, scale, mid, b, depth+1, result);
        } else result.add(b);
    }
    private static double yaw(TrackPose pose) { return Math.atan2(pose.tangent().x(), pose.tangent().z()); }
    private static double dot(TrackVector a, TrackVector b) { return a.x()*b.x()+a.z()*b.z(); }
    private static TrackVector add(TrackVector a, TrackVector b, double scale) {
        return new TrackVector(a.x()+b.x()*scale, a.y()+b.y()*scale, a.z()+b.z()*scale);
    }
    private static TrackVector shifted(TrackVector p, double yaw, double x, double z) {
        return new TrackVector(p.x()+Math.cos(yaw)*x+Math.sin(yaw)*z, p.y(), p.z()-Math.sin(yaw)*x+Math.cos(yaw)*z);
    }
}
