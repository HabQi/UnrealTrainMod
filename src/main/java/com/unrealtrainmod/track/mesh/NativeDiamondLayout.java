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
    private static final double WING_SETBACK = 0.0625;
    // Authored head faces: wingLr's inside edge at its joint, and wingLm/Rm's working face.
    private static final double WING_INNER_HEAD_X = -0.00419823, MIDDLE_WORK_X = 1.32916428;
    private static final double WING_REAR_END = -1.00021492;
    private static final double NOSE_TIP_X = 0.000016;
    private static final double RAIL_START = -0.8101502, RAIL_END = 0.80895454;
    private static final double RAIL_HALF_WIDTH = 0.07479225;
    private static final double JOINT_OVERLAP = 0.005;
    private static final double NOSE_X = 0.03129997, NOSE_END = 1.9996259;

    /** Authored cut angles, acute top corners and complete source bounds, in normalized metres. */
    private enum ShortWing {
        ANGLE_2_5("2_5", 4.0515653276815415, -0.61218834, -0.98867249,
                0.13194996, 1.32954239, -0.13005525, 1.33055298),
        ANGLE_5("5", 8.103755489266485, -0.46188690, -0.65045898,
                0.13207628, 1.33043167, -0.13106576, 1.33055313),
        ANGLE_10("10", 16.587985955601223, -0.27731493, -0.37469009,
                0.13025736, 1.33148270, -0.13194995, 1.33143738);

        static final double TIP_X = 0.01844046, MIN_X = -0.04206725;
        final String leftName, rightName;
        final double cutAngle, tipZ, minZ;
        final double leftMaxX, leftMaxZ, rightMinX, rightMaxZ;

        ShortWing(String suffix, double cutAngle, double tipZ, double minZ,
                  double leftMaxX, double leftMaxZ, double rightMinX, double rightMaxZ) {
            leftName = "wingL"+suffix+"_short"; rightName = "wingR"+suffix+"_short";
            this.cutAngle = cutAngle; this.tipZ = tipZ; this.minZ = minZ;
            this.leftMaxX = leftMaxX; this.leftMaxZ = leftMaxZ;
            this.rightMinX = rightMinX; this.rightMaxZ = rightMaxZ;
        }
        static ShortWing nearest(double degrees) {
            var selected = ANGLE_2_5;
            double difference = Math.abs(degrees-selected.cutAngle);
            // Ascending source angles make ties prefer the larger cut angle.
            for (var candidate : values()) {
                double next = Math.abs(degrees-candidate.cutAngle);
                if (next < difference || Math.abs(next-difference) <= 1e-7) {
                    selected = candidate; difference = next;
                }
            }
            return selected;
        }
        static ShortWing forName(String name) {
            for (var candidate : values())
                if (name.equals(candidate.leftName) || name.equals(candidate.rightName)) return candidate;
            return null;
        }
    }

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
    public record Part(String name, TrackVector position, double yaw, double localX, double localZ, long chunk,
                       double visibleLength, NativeRailCurve curve, double miterSlope, boolean startMiter) {
        public Part(String name, TrackVector position, double yaw, double localX, double localZ, long chunk,
                    double visibleLength, NativeRailCurve curve, double miterSlope) {
            this(name,position,yaw,localX,localZ,chunk,visibleLength,curve,miterSlope,false);
        }
        public Part(String name, TrackVector position, double yaw, double localX, double localZ, long chunk) {
            this(name, position, yaw, localX, localZ, chunk, 0, null, 0);
        }
        public Part(String name, TrackVector position, double yaw, double localX, double localZ, long chunk,
                    double visibleLength, NativeRailCurve curve) {
            this(name, position, yaw, localX, localZ, chunk, visibleLength, curve, 0);
        }
        public Matrix4f transform(TrackVector anchor, double scale) {
            return new Matrix4f().translation((float)(position.x()-anchor.x()), (float)(position.y()-anchor.y()),
                    (float)(position.z()-anchor.z())).rotateY((float)yaw)
                    .translate((float)(-localX*scale), 0, (float)(-localZ*scale)).scale((float)scale);
        }
    }
    private record Edge(double distance, TrackVector point) {}
    record Crossing(double first, double second, TrackVector point) {}
    public record RailEnd(int side, double distance, TrackVector point, double yaw, double miterSlope) {}
    private record SleeperRow(double second, TrackVector a, TrackVector b, double yaw, double across) {}
    private record SleeperBoundary(double distance, double yaw) {}
    record Footprint(List<TrackVector> hull, double minX, double minZ, double maxX, double maxZ) {
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
        final Map<Integer, Double> wingEnds = new HashMap<>(), noseEnds = new HashMap<>();
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

    public record SleeperGroup(Part part, boolean second, double distance, TrackVector rowOrigin) {}
    public record Core(NativeDiamondLayout layout, List<RailEnd> first, List<RailEnd> second,
                       boolean secondReversed, double firstCenter, double secondCenter, List<SleeperGroup> sleepers) {}

    /** The two acute working-face intersections bound the inner slip, independently of hardware preparation. */
    public record SlipEnd(TrackVector workingPoint, double firstStation, double secondStation) {}
    public record SlipEnds(SlipEnd entrance, SlipEnd exit, int firstInnerSide, List<TrackVector> boundary) {
        public SlipEnds { boundary = List.copyOf(boundary); }
    }

    public static SlipEnds slipEnds(TrackGeometry first, TrackGeometry second, double gauge) {
        if (!Double.isFinite(gauge) || gauge <= 0) throw new InvalidLayout(Problem.SPACE);
        var a = new Path(first); var b = new Path(second);
        double scale = gauge / 1.435;
        var center = crossing(a, b, 0, 0, scale);
        var ca = a.at(center.first()); var cb = b.at(center.second());
        angle(ca, cb);
        // Port order is significant: A/C must enter and B/D must leave the same crossing direction.
        if (dot(ca.tangent(), cb.tangent()) <= 0) throw new InvalidLayout(Problem.INTERSECTION);
        int inner = dot(cb.tangent(), ca.lateral()) > 0 ? -1 : 1;
        double offset = inner * NativeTurnoutLayout.RAIL_WORK_X * scale;
        var entrance = crossing(a, b, offset, -offset, scale);
        var exit = crossing(a, b, -offset, offset, scale);
        var upper = crossing(a, b, offset, offset, scale);
        var lower = crossing(a, b, -offset, -offset, scale);
        if (entrance.first() >= center.first() || entrance.second() >= center.second()
                || exit.first() <= center.first() || exit.second() <= center.second()) throw new InvalidLayout(Problem.INTERSECTION);
        for (var joint : List.of(upper, lower)) {
            if (joint.first() <= entrance.first() || joint.first() >= exit.first()
                    || joint.second() <= entrance.second() || joint.second() >= exit.second()) throw new InvalidLayout(Problem.INTERSECTION);
        }
        for (var joint : List.of(entrance, exit, upper, lower)) {
            var pa = a.at(joint.first()); var pb = b.at(joint.second());
            angle(pa, pb);
            if (Math.abs(offset * pa.curvature()) >= 1 || Math.abs(offset * pb.curvature()) >= 1) throw new InvalidLayout(Problem.SPACE);
        }
        var boundary = new ArrayList<TrackVector>();
        slipBoundary(boundary, a, offset, entrance.first(), upper.first(), scale);
        slipBoundary(boundary, b, offset, upper.second(), exit.second(), scale);
        slipBoundary(boundary, a, -offset, exit.first(), lower.first(), scale);
        slipBoundary(boundary, b, -offset, lower.second(), entrance.second(), scale);
        return new SlipEnds(new SlipEnd(entrance.point(), entrance.first(), entrance.second()),
                new SlipEnd(exit.point(), exit.first(), exit.second()), inner, boundary);
    }

    private static void slipBoundary(List<TrackVector> result, Path path, double offset, double from, double to, double scale) {
        result.add(path.edge(from, offset));
        var edges = flatten(path, offset, scale);
        if (from < to) {
            for (var edge : edges) if (edge.distance() > from && edge.distance() < to) result.add(edge.point());
        } else {
            for (int i = edges.size() - 1; i >= 0; i--) {
                var edge = edges.get(i);
                if (edge.distance() < from && edge.distance() > to) result.add(edge.point());
            }
        }
    }
    public static Core core(TrackGeometry first, TrackGeometry second, double gauge) {
        var result = new ArrayList<Core>();
        build(first, second, gauge, result);
        return result.getFirst();
    }
    private static NativeDiamondLayout build(TrackGeometry first, TrackGeometry second, double gauge) {
        return build(first, second, gauge, null);
    }
    private static NativeDiamondLayout build(TrackGeometry first, TrackGeometry second, double gauge, List<Core> result) {
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
                var middleAnchor = middle(parts, joint.point(), pa, pb, sa, scale);
                obtuse(parts, a, b, joint, middleAnchor, sa, scale);
                double slope = -sa*Math.tan(Math.toRadians(degrees)/2);
                a.railEnds.add(new RailEnd(sa, joint.first(), joint.point(), yaw(pa), slope));
                b.railEnds.add(new RailEnd(sb, joint.second(), joint.point(), yaw(pb), slope));
            }
        }
        connections(parts, a, scale); connections(parts, b, scale);
        var core = parts.stream().map(part -> coreFootprint(part, scale)).toList();
        var ordinary = new ArrayList<OrdinaryRun>();
        var sleeperGroups = result == null ? null : new ArrayList<SleeperGroup>();
        if (result == null) {
            moduleFootprints(a, scale); moduleFootprints(b, scale);
            ordinaryRuns(a, b, center, core, scale, ordinary, parts);
            fixedTrack(parts, a, scale); fixedTrack(parts, b, scale);

            sleepers(parts, a, b, center, scale, sleeperGroups);
        } else {
            for (var path : List.of(a, b)) {
                path.sleeperStart = path.railEnds.stream().mapToDouble(RailEnd::distance).min().orElseThrow()-0.5*scale;
                path.sleeperEnd = path.railEnds.stream().mapToDouble(RailEnd::distance).max().orElseThrow()+0.5*scale;
            }
            sleepers(parts, a, b, center, scale, sleeperGroups);
        }
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
        var layout = new NativeDiamondLayout(a.at(0).position(), scale, parts, ordinary, chunks,
                new AABB(minX, height-1.5*scale, minZ, maxX, height+0.25*scale, maxZ), a.poses.size()+b.poses.size()+reversedSamples);
        if (result != null) result.add(new Core(layout, List.copyOf(a.railEnds), List.copyOf(b.railEnds),
                b.geometry != second, center.first(), center.second(), List.copyOf(sleeperGroups)));
        return layout;
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

    private static void ordinaryRuns(Path a, Path b, Crossing center, List<Footprint> core, double scale, List<OrdinaryRun> runs, List<Part> parts) {
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
                if (!onA && previousA != null) onA = ordinaryRun(a, b, currentA, previousA, direction, core, scale, runs, parts);
                if (!onB && currentB != null && previousB != null)
                    onB = ordinaryRun(b, a, currentB, previousB, direction, core, scale, runs, parts);
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
                    if (previous != null && ordinaryRun(b, a, current, previous, direction, core, scale, runs, parts)) break;
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
                                        List<Footprint> core, double scale, List<OrdinaryRun> runs, List<Part> parts) {
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
        double lastCenter = firstCenter+direction*(count-1)*period;
        double outer = lastCenter+direction*RAIL_END*scale;
        double from = direction < 0 ? outer : path.coreEnd, to = direction < 0 ? path.coreStart : outer;
        for (int side : new int[]{-1,1}) addRails(parts,path.geometry,from,to,side,scale,0,0,null,null);
        return true;
    }

    private static boolean separate(Footprint module, List<Footprint> other, List<Footprint> core) {
        for (var footprint : other) if (module.intersects(footprint)) return false;
        for (var footprint : core) if (module.intersects(footprint)) return false;
        return true;
    }

    static Footprint coreFootprint(Part part, double scale) {
        if (part.curve() != null) {
            var points = new ArrayList<TrackVector>();
            for (var knot : part.curve().knots()) {
                var point = shifted(part.position(), part.yaw(), knot.point().x()*scale, knot.point().z()*scale);
                // Rail and nose cross sections extend less than 75 mm from their reference line.
                for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1})
                    points.add(new TrackVector(point.x()+x*0.075*scale, point.y(), point.z()+z*0.075*scale));
            }
            return footprint(points);
        }
        // Reference bounds of the existing complete r11 components, including guard fasteners.
        double minX, maxX, minZ, maxZ;
        var shortWing = ShortWing.forName(part.name());
        if (part.name().startsWith("nose")) {
            minX = -0.106092; maxX = 0.106092; minZ = 0; maxZ = NOSE_END;
        } else if (part.name().equals("guardL_short") || part.name().equals("guardR_short")) {
            minX = 1.10516998; maxX = 1.36041428; minZ = -0.83420723; maxZ = 1.42413879;
            if (part.name().equals("guardR_short")) { minX = -1.36041428; maxX = -1.10516998; }
        } else if (part.name().equals("wingFrontL_short") || part.name().equals("wingFrontR_short")) {
            minX = -0.04982666; maxX = 0.12087976; minZ = -0.00000060; maxZ = 1.42720108;
            if (part.name().equals("wingFrontR_short")) { minX = -0.11926294; maxX = 0.04982666; maxZ = 1.42720123; }
        } else if (part.name().equals("wingLm") || part.name().equals("wingRm")) {
            minX = 1.10516998; maxX = 1.36041428; minZ = -1.19073715; maxZ = 0.02806633;
            if (part.name().equals("wingRm")) { minZ = -0.02806633; maxZ = 1.19017517; }
        } else if (shortWing != null) {
            boolean right = part.name().equals(shortWing.rightName);
            minX = right ? shortWing.rightMinX : ShortWing.MIN_X;
            maxX = right ? -ShortWing.MIN_X : shortWing.leftMaxX;
            minZ = shortWing.minZ; maxZ = right ? shortWing.rightMaxZ : shortWing.leftMaxZ;
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

    static Footprint footprint(List<TrackVector> points) {
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
        String variant = noseVariant(noseAngle);
        boolean right = Math.sin(yb-ya) > 0;
        addPart(parts, (right ? "noseR" : "noseL")+variant, joint.point(), ya, 0, 0);
        addPart(parts, (right ? "noseL" : "noseR")+variant, joint.point(), yb, 0, 0);
        a.railEnds.add(new RailEnd(sa, joint.first()+direction*NOSE_END*scale,
                shifted(joint.point(), ya, (right ? 1 : -1)*NOSE_X*scale, NOSE_END*scale), ya-(direction < 0 ? Math.PI : 0), 0));
        b.railEnds.add(new RailEnd(sb, joint.second()+direction*NOSE_END*scale,
                shifted(joint.point(), yb, (right ? -1 : 1)*NOSE_X*scale, NOSE_END*scale), yb-(direction < 0 ? Math.PI : 0), 0));
        double gap = WING_SETBACK*scale/Math.sin(Math.toRadians(degrees));
        requireSpace(a, joint.first(), 3.5*scale+gap);
        requireSpace(b, joint.second(), 3.5*scale+gap);
        // As on the ordinary turnout, each rear follows the opposite path into the shared joint.
        double rearA = direction(a, joint.first()-direction*scale, joint.first());
        double rearB = direction(b, joint.second()-direction*scale, joint.second());
        var endB = wing(parts, right ? "R" : "L", shifted(joint.point(), baseB, 0, -gap), ya, rearB, scale);
        var endA = wing(parts, right ? "L" : "R", shifted(joint.point(), baseA, 0, -gap), yb, rearA, scale);
        a.wingEnds.put(sa, innerStation(a, sa, endA,
                joint.first()-direction*(gap-WING_REAR_END*scale), scale));
        b.wingEnds.put(sb, innerStation(b, sb, endB,
                joint.second()-direction*(gap-WING_REAR_END*scale), scale));
    }

    private static void obtuse(List<Part> parts, Path a, Path b, Crossing joint, TrackVector middleAnchor, int side, double scale) {
        var pa = a.at(joint.first()); var pb = b.at(joint.second());
        double ya = yaw(pa), yb = yaw(pb);
        boolean right = Math.sin(yb-ya) > 0;
        int outwardA = -side*(right ? 1 : -1), outwardB = -outwardA;
        obtusePair(parts, a, b, middleAnchor, side, joint.first(), joint.second(), !right, outwardB, -outwardA,
                right ? "noseR" : "noseL", (right ? 1 : -1)*NOSE_X, scale);
        obtusePair(parts, b, a, middleAnchor, side, joint.second(), joint.first(), right, outwardA, -outwardB,
                right ? "noseL" : "noseR", (right ? -1 : 1)*NOSE_X, scale);
    }

    private static void obtusePair(List<Part> parts, Path nosePath, Path wingPath, TrackVector middleAnchor, int side,
                                  double noseStation, double wingStation, boolean leftWing, int wingDirection, int noseDirection,
                                  String noseName, double center, double scale) {
        double noseTip = (noseName.equals("noseL") ? 1 : -1)*NOSE_TIP_X;
        double noseOffset = side*RAIL_X*scale+noseDirection*(noseTip-center)*scale;
        // Both head tips share the same horizontal target on the middle wing's working-face extension.
        var middleNormal = wingPath.at(wingStation).lateral();
        double tip = noseStation;
        for (int iteration = 0; iteration < 12; iteration++) {
            var tipPose = nosePath.at(tip);
            double error = dot(add(nosePath.edge(tip, noseOffset), middleAnchor, -1), middleNormal);
            if (Math.abs(error) <= 0.000001*scale) break;
            double derivative = dot(tipPose.tangent(), middleNormal)*(1-noseOffset*tipPose.curvature());
            tip = Math.clamp(tip-error/derivative, 0, nosePath.length());
        }
        if (Math.abs(dot(add(nosePath.edge(tip, noseOffset), middleAnchor, -1), middleNormal)) > 0.00001*scale)
            throw new InvalidLayout(Problem.SPACE);
        var target = nosePath.edge(tip, noseOffset);
        double station = innerStation(wingPath, side, target, wingStation, scale);
        double wingYaw = yaw(wingPath.at(station))+(wingDirection < 0 ? Math.PI : 0);
        double front = direction(nosePath, tip, tip+noseDirection*2*scale);
        double rear = direction(wingPath, station, station+wingDirection*2*scale);
        double degrees = Math.toDegrees(Math.acos(Math.clamp(Math.abs(Math.cos(rear-front)), 0, 1)));
        // Source cuts are measured against one branch, so match the half-angle of the local V joint.
        double halfAngle = Math.toDegrees(Math.acos(Math.clamp(Math.abs(dot(nosePath.at(tip).tangent(),
                wingPath.at(station).tangent())), 0, 1)))/2;
        var profile = ShortWing.nearest(halfAngle);
        var wingTarget = new TrackVector(target.x(), target.y()-0.002, target.z());
        addPart(parts, leftWing ? profile.leftName : profile.rightName, wingTarget, wingYaw,
                (leftWing ? 1 : -1)*ShortWing.TIP_X, profile.tipZ);
        nose(parts, nosePath, side, tip, noseDirection, noseName+noseVariant(degrees), center, scale);
    }

    private static void nose(List<Part> parts, Path path, int side, double tip, int direction,
                             String name, double center, double scale) {
        double tail = tip+direction*NOSE_END*scale;
        var position = path.edge(tip, side*RAIL_X*scale);
        double heading = yaw(path.at(tip))+(direction < 0 ? Math.PI : 0);
        parts.add(new Part(name, position, heading, center, 0, chunk(position), 0,
                stockCurve(path, side, tip, tail, position, heading, scale)));
        path.noseEnds.put(side, tail);
    }

    private static String noseVariant(double degrees) {
        if (degrees+1e-7 < 0.5 || degrees > 45+1e-7) throw new InvalidLayout(Problem.ANGLE);
        double angle = degrees+1e-7;
        return angle < 5 ? "2_5" : angle < 10 ? "5" : angle < 20 ? "10" : "20";
    }

    private static double innerStation(Path path, int side, TrackVector point, double guess, double scale) {
        double s = Math.clamp(guess, 0, path.length());
        for (int iteration = 0; iteration < 12; iteration++) {
            var pose = path.at(s); var edge = path.edge(s, side*RAIL_X*scale);
            double delta = dot(add(point, edge, -1), pose.tangent())/(1-side*RAIL_X*scale*pose.curvature());
            s = Math.clamp(s+delta, 0, path.length());
            if (Math.abs(delta) < 0.000001*scale) break;
        }
        return s;
    }

    private static void connections(List<Part> parts, Path path, double scale) {
        for (int side : new int[]{-1, 1}) {
            double wing = path.wingEnds.get(side), nose = path.noseEnds.get(side);
            // At large crossing angles the complete nose can already cover the rear wing endpoint.
            var joint = path.railEnds.stream().filter(end -> end.side() == side && end.miterSlope() != 0).findFirst().orElseThrow();
            if ((joint.distance()-wing)*(nose-wing) <= 0) continue;
            double start = Math.min(wing, nose), length = Math.abs(nose-wing);
            addRails(parts,path.geometry,start,start+length,side,scale,0,0,null,null);

        }
    }

    private static NativeRailCurve stockCurve(Path path, int side, double from, double to,
                                               TrackVector origin, double heading, double scale) {
        double length = Math.abs(to-from)/scale, parameter = (to-from)/length;
        double offset = side*RAIL_X*scale;
        int count = Math.max(1, (int)Math.ceil(length/0.1));
        var boundaries = new ArrayList<Double>(count+1);
        for (int i = 0; i <= count; i++) boundaries.add(i == count ? length : length*i/count);
        var curve = NativeRailCurve.fit(distance -> {
            double s = from+distance*parameter;
            var pose = path.at(s); var t = pose.tangent(); var lateral = pose.lateral();
            double curvature = pose.curvature(), factor = 1-offset*curvature;
            double lo = Math.max(0, s-0.001*scale), hi = Math.min(path.length(), s+0.001*scale);
            double derivative = (path.at(hi).curvature()-path.at(lo).curvature())/(hi-lo);
            var first = new TrackVector(t.x()*factor*parameter, 0, t.z()*factor*parameter);
            double normal = curvature*factor, squared = parameter*parameter;
            var second = new TrackVector((lateral.x()*normal-t.x()*offset*derivative)*squared, 0,
                    (lateral.z()*normal-t.z()*offset*derivative)*squared);
            return new NativeRailCurve.Knot(distance, path.edge(s, offset), first, second);
        }, boundaries, 0.00001*scale);
        return curve.local(0, length, origin, heading, 0, scale, length);
    }

    private static TrackVector middle(List<Part> parts, TrackVector joint, TrackPose a, TrackPose b, int side, double scale) {
        // Match the acute wing's head clearance, including its half-gauge reference and source head offset.
        double inset = (RAIL_X-1.435/2+WING_SETBACK+WING_INNER_HEAD_X)*scale;
        double shift = -side*inset/(1+dot(a.tangent(), b.tangent()));
        var anchor = add(add(joint, a.lateral(), shift), b.lateral(), shift);
        double ya = yaw(a), yb = yaw(b), reverse = side < 0 ? Math.PI : 0;
        boolean right = Math.sin(yb-ya) > 0;
        double halfAngle = Math.acos(Math.clamp(dot(a.tangent(), b.tangent()), -1, 1))/2;
        // Keep the original anchors; statically miter both source ends on their common bisector.
        double slope = Math.tan(halfAngle);
        // Reverse each frame and exchange the longitudinal variants so fasteners face into the diamond.
        parts.add(new Part(right ? "wingRm" : "wingLm", anchor, ya+reverse, MIDDLE_WORK_X, 0, chunk(anchor), 0, null, slope));
        parts.add(new Part(right ? "wingLm" : "wingRm", anchor, yb+reverse, MIDDLE_WORK_X, 0, chunk(anchor), 0, null, slope));
        return anchor;
    }

    private static double direction(Path path, double from, double to) {
        var start = path.at(from).position();
        var end = path.at(to).position();
        return Math.atan2(end.x()-start.x(), end.z()-start.z());
    }

    private static TrackVector wing(List<Part> parts, String side, TrackVector origin, double front, double rear, double scale) {
        double x = (side.equals("L") ? 1 : -1)*WING_X;
        // Both source parts retain the front frame; the guard's 3 mm vertical inset is independent of gauge.
        addPart(parts, "wingFront"+side+"_short", origin, front, 0, 0);
        var guard = new TrackVector(origin.x(), origin.y()-.003, origin.z());
        addPart(parts, "guard"+side+"_short", guard, front, 0, 0);
        var joint = shifted(origin, front, x*scale, 0);
        addPart(parts, "wing"+side+"r", shifted(joint, rear, 0, JOINT_OVERLAP*scale), rear, x, 0);
        return shifted(joint, rear, 0, (JOINT_OVERLAP+WING_REAR_END)*scale);
    }
    private static void sleepers(List<Part> parts, Path a, Path b, Crossing center, double scale, List<SleeperGroup> groups) {
        int first = (int)Math.ceil(-center.first()/scale), last = (int)Math.floor((a.length()-center.first())/scale);
        for (int index = first; index <= last; index++) {
            double s = center.first()+index*scale;
            var row = sleeperRow(a, b, s, scale);
            if (row == null) { unpairedSleeper(parts, a, b, s, scale, groups, false); continue; }
            boolean onA = coreSleeper(a, s), onB = coreSleeper(b, row.second());
            if (!onA && !onB) continue;
            var pa = a.at(s); var pb = b.at(row.second());
            if (dot(pa.tangent(), pb.tangent()) < 0.5
                    || Math.hypot(row.b().x()-pb.position().x(), row.b().z()-pb.position().z()) > 0.00001*scale)
                throw new InvalidLayout(Problem.SPACE);
            // Coincident rows use the stable path order; both complementary sources remain present.
            boolean right = row.across() >= -0.00001*scale;
            if (onA) {
                addPart(parts, right ? "sleeper_point02R" : "sleeper_point02L", row.a(), row.yaw(), 0, 0.25);
                if (groups != null) groups.add(new SleeperGroup(parts.getLast(), false, s, row.a()));
            }
            if (onB) {
                addPart(parts, right ? "sleeper_point02L" : "sleeper_point02R", row.b(), row.yaw(), 0, 0.25);
                if (groups != null) groups.add(new SleeperGroup(parts.getLast(), true, row.second(), row.a()));
            }
        }
        double otherCenter = center.second();
        first = (int)Math.ceil(-otherCenter/scale); last = (int)Math.floor((b.length()-otherCenter)/scale);
        for (int index = first; index <= last; index++) {
            double s = otherCenter+index*scale;
            if (coreSleeper(b, s) && sleeperRow(b, a, s, scale) == null) unpairedSleeper(parts, b, a, s, scale, groups, true);
        }
    }
    private static boolean coreSleeper(Path path, double s) { return s >= path.sleeperStart && s <= path.sleeperEnd; }
    private static void unpairedSleeper(List<Part> parts, Path path, Path other, double s, double scale,
                                        List<SleeperGroup> groups, boolean second) {
        if (!coreSleeper(path, s)) return;
        var pose = path.at(s);
        var start = other.at(0).position(); var end = other.at(other.length()).position();
        var nearest = Math.hypot(start.x()-pose.position().x(), start.z()-pose.position().z())
                < Math.hypot(end.x()-pose.position().x(), end.z()-pose.position().z()) ? start : end;
        double across = (nearest.x()-pose.position().x())*pose.lateral().x()+(nearest.z()-pose.position().z())*pose.lateral().z();
        addPart(parts, across >= -0.00001*scale ? "sleeper_point02R" : "sleeper_point02L", pose.position(), yaw(pose), 0, 0.25);
        if (groups != null) groups.add(new SleeperGroup(parts.getLast(), second, s, pose.position()));
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
        double from = fromStart ? path.coreStart : end.distance(), to = fromStart ? end.distance() : path.coreEnd;
        if (to-from <= 1e-6*scale) return;
        addRails(parts,path.geometry,from,to,end.side(),scale,fromStart ? 0 : end.miterSlope(),
                fromStart ? end.miterSlope() : 0,fromStart ? null : end,fromStart ? end : null);
    }
    private static void addRails(List<Part> parts, TrackGeometry geometry, double from, double to, int side, double scale,
                                 double startMiter, double endMiter, RailEnd startPort, RailEnd endPort) {
        double offset = side*RAIL_X*scale;
        var a = NativeFixedRails.pose(geometry,from); var b = NativeFixedRails.pose(geometry,to);
        var start = startPort == null ? NativeFixedRails.edge(geometry,from,offset) : startPort.point();
        var end = endPort == null ? NativeFixedRails.edge(geometry,to,offset) : endPort.point();
        double ya = startPort == null ? yaw(a) : startPort.yaw(), yb = endPort == null ? yaw(b) : endPort.yaw();
        var reference = NativeFixedRails.reference(geometry,from,to,offset,start,end,ya,yb,scale);
        var cuts = NativeFixedRails.cuts(reference,scale,startMiter,endMiter);
        for (int i = 1; i < cuts.size(); i++) {
            double low = cuts.get(i-1), high = cuts.get(i), span = (high-low)/scale;
            var pose = reference.at(low); var position = pose.point(); double heading = Math.atan2(pose.first().x(),pose.first().z());
            double headMiter = i == 1 ? startMiter : 0, tailMiter = i+1 == cuts.size() ? endMiter : 0;
            var curve = reference.local(low,high,position,heading,0,scale,span,.075*scale*Math.abs(headMiter),.075*scale*Math.abs(tailMiter));
            // A miter is needed only at the protected core port. Ordinary strip joins share their end section.
            double slope = i == 1 && startMiter != 0 ? startMiter : i+1 == cuts.size() ? endMiter : 0;
            parts.add(new Part(side > 0 ? "railL" : "railR",position,heading,side*RAIL_X,-span/2,chunk(position),span,curve,slope,
                    i == 1 && startMiter != 0));
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

    static boolean intersects(TrackGeometry a, TrackGeometry b, double scale) {
        return crossing(new Path(a), new Path(b), 0, 0, scale, false) != null;
    }
    private static Crossing crossing(Path a, Path b, double offsetA, double offsetB, double scale) {
        return crossing(a, b, offsetA, offsetB, scale, true);
    }
    private static Crossing crossing(Path a, Path b, double offsetA, double offsetB, double scale, boolean required) {
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
        if (result == null && required) throw new InvalidLayout(Problem.INTERSECTION);
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
