/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;

/** A resource-independent, immutable assembly of rigid r11 parts. Sampling ends when build returns. */
public record NativeTurnoutLayout(TrackVector origin, double scale, List<Part> parts, int samples) {
    public NativeTurnoutLayout { parts = List.copyOf(parts); }

    // Connection sections measured from the normalized r11 OBJ, not its object origins.
    private static final double RAIL_X = 0.74875, STOCK_WORK_X = 0.7234168;
    private static final double BLADE_TIP = -1.572079, BLADE_JOIN = 0.65;
    private static final double BLADE_OPENING = 0.12;
    private static final double WING_X = 0.0311652, WING_REAR = -1.000215, NOSE_END = 1.999626;
    private static final double RAIL_STEP = 1.45;

    public enum Problem { FROG, SPACE }
    public static final class InvalidLayout extends IllegalArgumentException {
        private final Problem problem;
        public InvalidLayout(Problem problem) { super(problem.name()); this.problem = problem; }
        public Problem problem() { return problem; }
    }

    /** Connection in world/model space; positive visibleLength trims fixed rails in normalized model units. */
    public record Part(String name, TrackVector position, double yaw, double pitch,
                       double localX, double localZ, double visibleLength, Motion motion) {
        public boolean moving() { return motion != null; }
        public Matrix4f transform(TrackVector anchor, double scale, float movement) {
            var point = motion == null ? position : motion.start(movement);
            double direction = motion == null ? yaw : motion.direction(movement);
            return new Matrix4f().translation((float)(point.x() - anchor.x()),
                    (float)(point.y() - anchor.y()), (float)(point.z() - anchor.z()))
                    .rotateY((float)direction).rotateX((float)-pitch)
                    .translate((float)(-localX * scale), 0, (float)(-localZ * scale)).scale((float)scale);
        }
    }

    /** Connected rigid links evaluated from the fixed root toward the tip. No geometry/path access. */
    public static final class Motion {
        private final Motion next;
        private final TrackVector root;
        private final double horizontal, rise, yaw, travel;
        private final boolean reverse;
        private float cachedMovement = Float.NaN;
        private TrackVector cachedStart;

        private Motion(Motion next, TrackVector end, double length, double yaw, double pitch,
                       double travel, boolean reverse) {
            this.next = next; root = end; horizontal = length * Math.cos(pitch); rise = length * Math.sin(pitch);
            this.yaw = yaw; this.travel = travel; this.reverse = reverse;
        }
        public double direction(float movement) { return yaw + travel * (reverse ? 1 - movement : movement); }
        public TrackVector start(float movement) {
            if (cachedMovement != movement) {
                var end = next == null ? root : next.start(movement);
                double angle = direction(movement);
                cachedStart = new TrackVector(end.x() - Math.sin(angle) * horizontal,
                        end.y() - rise, end.z() - Math.cos(angle) * horizontal);
                cachedMovement = movement;
            }
            return cachedStart;
        }
    }

    private static final class Path {
        final TrackGeometry geometry;
        final Map<Double, TrackPose> poses = new HashMap<>();
        Path(TrackGeometry geometry) { this.geometry = geometry; }
        double length() { return geometry.getLength(); }
        TrackPose at(double s) { return poses.computeIfAbsent(Math.clamp(s, 0, length()), geometry::sample); }
        TrackVector edge(double s, double offset) {
            var p = at(s);
            return new TrackVector(p.position().x() + p.lateral().x() * offset, p.position().y(),
                    p.position().z() + p.lateral().z() * offset);
        }
    }
    private record Edge(double distance, TrackVector point) {}
    private record Crossing(double main, double branch, TrackVector point) {}
    private record Frame(TrackVector start, TrackVector end, double yaw, double pitch, double length) {}
    private record Frog(TrackVector incoming, TrackVector outgoing) {}
    private record Joint(TrackVector closed, TrackVector open) {}

    public static NativeTurnoutLayout build(TrackAssemblyDefinition.Geometry geometry, double gauge) {
        return build(geometry.paths().get("straight").getFirst(), geometry.paths().get("diverging").getFirst(), gauge);
    }

    public static NativeTurnoutLayout example() {
        return build(new BezierTrackGeometry(new TrackPoint(0, 0), new TrackPoint(0, 12),
                        new TrackPoint(0, 24), new TrackPoint(0, 36), new LinearGrade(0, 0), 1.435),
                new BezierTrackGeometry(new TrackPoint(0, 0), new TrackPoint(0, 12),
                        new TrackPoint(4.5, 24), new TrackPoint(7, 36), new LinearGrade(0, 0), 1.435), 1.435);
    }

    private static NativeTurnoutLayout build(TrackGeometry mainGeometry, TrackGeometry branchGeometry, double gauge) {
        var main = new Path(mainGeometry); var branch = new Path(branchGeometry);
        double scale = gauge / 1.435;
        var end = main.at(main.length()); var branchEnd = branch.at(branch.length()).position();
        double separation = (branchEnd.x() - end.position().x()) * end.lateral().x()
                + (branchEnd.z() - end.position().z()) * end.lateral().z();
        double side = Math.copySign(1, separation);
        var crossing = crossing(main, branch, side * gauge / 2, scale);
        if (crossing.main() < 6 * scale || crossing.branch() < 6 * scale
                || main.length() - crossing.main() < 3 * scale || branch.length() - crossing.branch() < 3 * scale) {
            throw new InvalidLayout(Problem.SPACE);
        }
        var parts = new LinkedHashSet<Part>();
        double mainYaw = yaw(main.at(crossing.main())), branchYaw = yaw(branch.at(crossing.branch()));
        double angle = Math.abs(Math.toDegrees(wrap(mainYaw - branchYaw)));
        if (angle < 0.5 || angle > 45) throw new InvalidLayout(Problem.SPACE);
        double mainFront = direction(main, crossing.main(), crossing.main() + 2 * scale);
        double branchFront = direction(branch, crossing.branch(), crossing.branch() + 2 * scale);
        double noseAngle = Math.abs(Math.toDegrees(wrap(mainFront - branchFront)));
        if (noseAngle < 0.5 || noseAngle > 45) throw new InvalidLayout(Problem.SPACE);
        String nose = noseAngle < 5 ? "2_5" : noseAngle < 10 ? "5" : noseAngle < 20 ? "10" : "20";
        String mainNose = side > 0 ? "noseR" : "noseL", branchNose = side > 0 ? "noseL" : "noseR";
        fixed(parts, mainNose + nose, crossing.point(), mainFront);
        fixed(parts, branchNose + nose, crossing.point(), branchFront);
        // Offset toward the incoming side of the crossing; the opposite sign displaces both check rails.
        double wingOffset = 0.0625 * scale / Math.sin(Math.toRadians(angle));
        var mainWingOrigin = shifted(crossing.point(), branchYaw, 0, -wingOffset);
        var branchWingOrigin = shifted(crossing.point(), mainYaw, 0, -wingOffset);
        String mainWing = side > 0 ? "wingR" : "wingL";
        String branchWing = side > 0 ? "wingL" : "wingR";
        var branchIncoming = wing(parts, mainWing, mainWingOrigin, mainFront,
                direction(branch, crossing.branch() - scale, crossing.branch()), -side * WING_X, scale);
        var mainIncoming = wing(parts, branchWing, branchWingOrigin, branchFront,
                direction(main, crossing.main() - scale, crossing.main()), side * WING_X, scale);
        var mainFrog = new Frog(mainIncoming, shifted(crossing.point(), mainFront, side * WING_X * scale, NOSE_END * scale));
        var branchFrog = new Frog(branchIncoming, shifted(crossing.point(), branchFront, -side * WING_X * scale, NOSE_END * scale));
        int divisions = Math.max(1, (int)Math.floor(main.length() / scale * 2));
        double toe = Math.floor(divisions * (crossing.main() / main.length() / 8 + 0.042)) * main.length() / divisions;
        double branchToe = transverseStation(branch, main.at(toe));
        rails(parts, main, crossing.main(), toe, side, false, mainFrog,
                branch.edge(branchToe, side * STOCK_WORK_X * scale), scale);
        rails(parts, branch, crossing.branch(), branchToe, -side, true, branchFrog,
                main.edge(toe, -side * STOCK_WORK_X * scale), scale);
        // A single source bed covers both tracks near the toe. Add the second row only outside its support width.
        for (var path : List.of(main, branch)) {
            int modules = (int)Math.ceil(path.length() / (2 * scale));
            for (int module = 0; module < modules; module++) {
                double s = module * 2 * scale;
                var pose = path.at(s);
                if (path == branch) {
                    var reference = main.at(transverseStation(main, pose));
                    double dx = pose.position().x() - reference.position().x();
                    double dz = pose.position().z() - reference.position().z();
                    double across = Math.abs(dx * reference.lateral().x() + dz * reference.lateral().z());
                    double along = Math.abs(dx * reference.tangent().x() + dz * reference.tangent().z());
                    if (across <= 2 * scale && along < scale) continue;
                }
                fixed(parts, "ballast0" + (module % 4 + 1), pose.position(), yaw(pose));
            }
        }
        double root = crossing.main() * (1 - 0.4 * crossing.main() / main.length());
        int sleeperCount = (int)Math.floor((crossing.main() + 1.5 * scale) / (2 * scale)) + 1;
        for (int index = 0; index < sleeperCount; index++) {
            double s = index * 2 * scale;
            var a = main.at(s);
            double other = transverseStation(branch, a);
            var b = branch.at(other);
            double direction = yaw(a) + wrap(yaw(b) - yaw(a)) / 2;
            boolean slide = s > toe - 2.5 * scale && s < root * 0.6;
            String kind = s >= crossing.main() ? "sleeper_point01" : slide ? "sleeper_point03" : "sleeper_point02";
            if (kind.equals("sleeper_point01")) {
                fixed(parts, kind, a.position(), yaw(a));
                fixed(parts, kind, b.position(), yaw(b));
                continue;
            }
            fixed(parts, kind + (side > 0 ? "R" : "L"), a.position(), direction);
            fixed(parts, kind + (side > 0 ? "L" : "R"), b.position(), direction);
        }
        return new NativeTurnoutLayout(main.at(0).position(), scale, new ArrayList<>(parts),
                main.poses.size() + branch.poses.size());
    }

    private static TrackVector wing(LinkedHashSet<Part> parts, String name, TrackVector origin,
                                    double front, double rear, double localX, double scale) {
        fixed(parts, name + "f", origin, front);
        var joint = shifted(origin, front, localX * scale, 0);
        var rearOrigin = shifted(joint, rear, -localX * scale, 0);
        fixed(parts, name + "r", rearOrigin, rear);
        return shifted(joint, rear, 0, WING_REAR * scale);
    }

    private static void rails(LinkedHashSet<Part> parts, Path path, double frog, double toe,
                              double inner, boolean reverse, Frog ports, TrackVector tip, double scale) {
        double incoming = station(path, ports.incoming(), path.at(frog).tangent());
        double outgoing = station(path, ports.outgoing(), path.at(frog).tangent());
        double root = frog * (1 - 0.4 * frog / path.length());
        double bladeLength = (BLADE_JOIN - BLADE_TIP) * scale;
        if (root - toe < bladeLength + 0.5 * scale || root >= incoming) throw new InvalidLayout(Problem.SPACE);
        double lo = toe, hi = root;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2;
            if (distance(tip, path.edge(mid, inner * RAIL_X * scale)) < bladeLength) lo = mid; else hi = mid;
        }
        double bladeEnd = (lo + hi) / 2;
        var joints = new ArrayList<Joint>();
        joints.add(joint(path, toe, tip, toe, root, inner, scale));
        int links = Math.max(1, (int)Math.ceil((root - bladeEnd) / (RAIL_STEP * scale)));
        for (int index = 0; index <= links; index++) {
            double s = index == links ? root : bladeEnd + (root - bladeEnd) * index / links;
            joints.add(joint(path, s, path.edge(s, inner * RAIL_X * scale), toe, root, inner, scale));
        }
        Motion next = null;
        var moving = new ArrayList<Part>();
        for (int i = joints.size() - 2; i >= 0; i--) {
            var a = joints.get(i); var b = joints.get(i + 1);
            var frame = frame(a.closed(), b.closed());
            if (i > 0 && frame.length() > 1.58 * scale) throw new InvalidLayout(Problem.SPACE);
            double openYaw = Math.atan2(b.open().x() - a.open().x(), b.open().z() - a.open().z());
            next = new Motion(next, b.closed(), frame.length(), frame.yaw(), frame.pitch(), wrap(openYaw - frame.yaw()), reverse);
            double localZ = i == 0 ? BLADE_TIP : -frame.length() / (2 * scale);
            moving.add(new Part(i == 0 ? (inner > 0 ? "L1" : "R1") : (inner > 0 ? "railL" : "railR"),
                    a.closed(), frame.yaw(), frame.pitch(), inner * RAIL_X, localZ, 0, next));
        }
        for (var part : moving.reversed()) parts.add(part);

        // A single integer grid owns normal sleepers and both of their embedded rails.
        double step = 1.5 * scale;
        int first = (int)Math.ceil((outgoing + 0.75 * scale) / step);
        double normalStart = first * step;
        TrackVector innerEnd = path.edge(path.length(), inner * RAIL_X * scale);
        TrackVector outerEnd = path.edge(path.length(), -inner * RAIL_X * scale);
        double boundary = path.length();
        if (normalStart < path.length()) {
            boundary = normalStart - 0.75 * scale;
            var firstFrame = centerFrame(path, normalStart, scale);
            innerEnd = localPoint(firstFrame, inner * RAIL_X * scale, -0.75 * scale);
            outerEnd = localPoint(firstFrame, -inner * RAIL_X * scale, -0.75 * scale);
            int last = (int)Math.ceil(path.length() / step) - 1;
            for (int index = first; index <= last; index++) ordinary(parts, path, index * step, scale);
            double tail = last * step + 0.75 * scale;
            if (tail < path.length()) {
                var lastFrame = centerFrame(path, last * step, scale);
                railRun(parts, path, tail, path.length(), localPoint(lastFrame, inner * RAIL_X * scale, 0.75 * scale),
                        path.edge(path.length(), inner * RAIL_X * scale), inner, scale);
                railRun(parts, path, tail, path.length(), localPoint(lastFrame, -inner * RAIL_X * scale, 0.75 * scale),
                        path.edge(path.length(), -inner * RAIL_X * scale), -inner, scale);
            }
        }
        railRun(parts, path, 0, boundary, path.edge(0, -inner * RAIL_X * scale), outerEnd, -inner, scale);
        railRun(parts, path, root, incoming, joints.getLast().closed(), ports.incoming(), inner, scale);
        railRun(parts, path, outgoing, boundary, ports.outgoing(), innerEnd, inner, scale);
    }

    private static Joint joint(Path path, double s, TrackVector point, double toe, double root, double inner, double scale) {
        double t = Math.clamp((s - toe) / (root - toe), 0, 1);
        double taper = (ease(1) - ease(t)) / ease(1);
        var lateral = path.at(s).lateral();
        double offset = -inner * BLADE_OPENING * scale * taper;
        return new Joint(point, new TrackVector(point.x() + lateral.x() * offset, point.y(), point.z() + lateral.z() * offset));
    }
    private static double ease(double t) { double x = t * 3.5; return x / Math.sqrt(1 + x * x); }

    private static void railRun(LinkedHashSet<Part> parts, Path path, double a, double b,
                                TrackVector start, TrackVector end, double side, double scale) {
        if (b < a) throw new InvalidLayout(Problem.SPACE);
        if (distance(start, end) < 1e-6 * scale) return;
        int count = Math.max(1, (int)Math.ceil((b - a) / (RAIL_STEP * scale)));
        var previous = start;
        for (int index = 1; index <= count; index++) {
            var point = index == count ? end : path.edge(a + (b - a) * index / count, side * RAIL_X * scale);
            var frame = frame(previous, point);
            if (frame.length() > 1.58 * scale) throw new InvalidLayout(Problem.SPACE);
            parts.add(new Part(side > 0 ? "railL" : "railR", previous, frame.yaw(), frame.pitch(),
                    side * RAIL_X, -frame.length() / (2 * scale), frame.length() / scale, null));
            previous = point;
        }
    }
    private static Frame frame(TrackVector a, TrackVector b) {
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        double horizontal = Math.hypot(dx, dz);
        return new Frame(a, b, Math.atan2(dx, dz), Math.atan2(dy, horizontal), Math.hypot(horizontal, dy));
    }
    private static Frame centerFrame(Path path, double s, double scale) {
        return frame(path.at(s - 0.75 * scale).position(), path.at(s + 0.75 * scale).position());
    }
    private static TrackVector localPoint(Frame f, double x, double z) {
        var center = new TrackVector((f.start().x() + f.end().x()) / 2,
                (f.start().y() + f.end().y()) / 2, (f.start().z() + f.end().z()) / 2);
        double forward = z * Math.cos(f.pitch());
        return new TrackVector(center.x() + Math.cos(f.yaw()) * x + Math.sin(f.yaw()) * forward,
                center.y() + Math.sin(f.pitch()) * z, center.z() - Math.sin(f.yaw()) * x + Math.cos(f.yaw()) * forward);
    }
    private static void ordinary(LinkedHashSet<Part> parts, Path path, double s, double scale) {
        var f = centerFrame(path, s, scale);
        parts.add(new Part("sleeper", localPoint(f, 0, 0), f.yaw(), f.pitch(), 0, 0, 0, null));
    }
    private static double distance(TrackVector a, TrackVector b) {
        return Math.hypot(Math.hypot(b.x() - a.x(), b.z() - a.z()), b.y() - a.y());
    }
    private static double direction(Path path, double a, double b) {
        return frame(path.at(a).position(), path.at(b).position()).yaw();
    }
    private static void fixed(LinkedHashSet<Part> parts, String name, TrackVector p, double yaw) {
        parts.add(new Part(name, p, yaw, 0, 0, 0, 0, null));
    }
    private static double yaw(TrackPose p) { return Math.atan2(p.tangent().x(), p.tangent().z()); }
    private static double wrap(double value) { return Math.atan2(Math.sin(value), Math.cos(value)); }
    private static TrackVector shifted(TrackVector p, double yaw, double x, double z) {
        return new TrackVector(p.x() + Math.cos(yaw) * x + Math.sin(yaw) * z, p.y(),
                p.z() - Math.sin(yaw) * x + Math.cos(yaw) * z);
    }
    private static double transverseStation(Path path, TrackPose reference) {
        return station(path, reference.position(), reference.tangent());
    }
    private static double station(Path path, TrackVector point, TrackVector tangent) {
        double lo = 0, hi = path.length();
        for (int i = 0; i < 16; i++) {
            double s = (lo + hi) / 2;
            var p = path.at(s).position();
            double along = (p.x() - point.x()) * tangent.x() + (p.z() - point.z()) * tangent.z();
            if (along < 0) lo = s; else hi = s;
        }
        double result = (lo + hi) / 2;
        if (path.at(result).tangent().x() * tangent.x() + path.at(result).tangent().z() * tangent.z() < 0.5) {
            throw new InvalidLayout(Problem.SPACE);
        }
        return result;
    }

    private static Crossing crossing(Path main, Path branch, double offset, double scale) {
        var a = flatten(main, offset, scale); var b = flatten(branch, -offset, scale);
        Crossing result = null;
        for (int i = 1; i < a.size(); i++) for (int j = 1; j < b.size(); j++) {
            var p = a.get(i - 1); var q = b.get(j - 1);
            var pe = a.get(i); var qe = b.get(j);
            double rx = pe.point().x() - p.point().x(), rz = pe.point().z() - p.point().z();
            double sx = qe.point().x() - q.point().x(), sz = qe.point().z() - q.point().z();
            double denominator = rx * sz - rz * sx;
            if (Math.abs(denominator) < 1e-12) continue;
            double dx = q.point().x() - p.point().x(), dz = q.point().z() - p.point().z();
            double t = (dx * sz - dz * sx) / denominator, u = (dx * rz - dz * rx) / denominator;
            if (t < 0 || t > 1 || u < 0 || u > 1) continue;
            var candidate = new Crossing(p.distance() + t * (pe.distance() - p.distance()),
                    q.distance() + u * (qe.distance() - q.distance()),
                    new TrackVector(p.point().x() + t * rx, p.point().y(), p.point().z() + t * rz));
            if (result != null && Math.abs(result.main() - candidate.main()) > 0.01 * scale) {
                throw new InvalidLayout(Problem.SPACE);
            }
            result = candidate;
        }
        if (result == null) throw new InvalidLayout(Problem.FROG);
        return result;
    }

    private static List<Edge> flatten(Path path, double offset, double scale) {
        var result = new ArrayList<Edge>();
        var first = new Edge(0, path.edge(0, offset));
        result.add(first);
        flatten(path, offset, scale, first, new Edge(path.length(), path.edge(path.length(), offset)), 0, result);
        return result;
    }
    private static void flatten(Path path, double offset, double scale, Edge a, Edge b, int depth, List<Edge> output) {
        double s = (a.distance() + b.distance()) / 2;
        var middle = new Edge(s, path.edge(s, offset));
        double dx = middle.point().x() - (a.point().x() + b.point().x()) / 2;
        double dz = middle.point().z() - (a.point().z() + b.point().z()) / 2;
        if (depth < 16 && (b.distance() - a.distance() > 2 * scale || Math.hypot(dx, dz) > 0.0005 * scale)) {
            flatten(path, offset, scale, a, middle, depth + 1, output);
            flatten(path, offset, scale, middle, b, depth + 1, output);
        } else output.add(b);
    }
}
