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
    private static final double HEAD_HALF = 0.0354982;
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

    /** Connection in world/model space; visibleLength clips rails during source or static page preparation. */
    public record Part(String name, TrackVector position, double yaw, double pitch,
                       double localX, double localZ, double visibleLength, float bladeLength,
                       BladeContact bladeContact, RailBend railBend, BedCut bedCut, Motion motion) {
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
        TrackPose extended(double s) {
            var pose = at(s);
            double extra = s - Math.clamp(s, 0, length());
            if (extra == 0) return pose;
            var p = pose.position(); var t = pose.tangent();
            return new TrackPose(new TrackVector(p.x() + extra * t.x(), p.y(), p.z() + extra * t.z()),
                    t, pose.lateral(), pose.normal(), pose.curvature(), pose.gauge());
        }
    }
    /** Keep x*localX + z*localZ + offset >= 0 in the normalized source mesh. */
    public record BedCut(double x, double z, double offset) {}
    public record BladeEdge(float distance, float offset) {}
    public record BladeContact(List<BladeEdge> edges) {
        public BladeContact { edges = List.copyOf(edges); }
    }
    public record RailBend(float start, float end) {}
    public record RowFrame(int index, double mainDistance, double branchDistance,
                           TrackVector main, TrackVector branch, double yaw) {}
    private record Edge(double distance, TrackVector point) {}
    private record Crossing(double main, double branch, TrackVector point) {}
    private record Frame(TrackVector start, TrackVector end, double yaw, double pitch, double length) {}
    private record Transition(List<TrackVector> points, List<Double> tangents) {}
    private record Frog(TrackVector incoming, TrackVector outgoing) {}

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
        double root = crossing.main() * (1 - 0.4 * crossing.main() / main.length());
        var rows = new ArrayList<RowFrame>();
        var lastMain = main.at(main.length()); var lastBranch = branch.at(branch.length());
        double extra = Math.max(0, (lastBranch.position().x() - lastMain.position().x()) * lastMain.tangent().x()
                + (lastBranch.position().z() - lastMain.position().z()) * lastMain.tangent().z());
        int rowCount = (int)Math.ceil((main.length() + extra) / (2 * scale)) + 2;
        double mainOut = station(main, mainFrog.outgoing(), main.at(crossing.main()).tangent());
        double branchOut = station(branch, branchFrog.outgoing(), branch.at(crossing.branch()).tangent());
        RowFrame normal = null;
        RowFrame bladeRow = null;
        for (int index = 0; index < rowCount; index++) {
            var row = row(main, branch, index * 2 * scale, index, scale);
            rows.add(row);
            if (normal == null && row.mainDistance() - 0.5 * scale > mainOut
                    && row.branchDistance() - 0.5 * scale > branchOut) normal = row;
            if (normal != null) continue;
            double s = row.mainDistance();
            boolean slide = s > toe - 2.5 * scale && s < root * 0.6;
            String kind = s >= crossing.main() ? "sleeper_point01" : slide ? "sleeper_point03" : "sleeper_point02";
            if (slide && bladeRow == null) {
                for (int offset = 0; offset < 4; offset++) {
                    double firstRow = s + (-0.5 + offset * 0.5) * scale;
                    if (firstRow >= 0) {
                        double shift = (-0.5 + offset * 0.5) * scale;
                        var a = shifted(row.main(), row.yaw(), 0, shift);
                        var b = shifted(row.branch(), row.yaw(), 0, shift);
                        bladeRow = new RowFrame(-1, station(main, a, main.at(s).tangent()),
                                station(branch, b, branch.at(row.branchDistance()).tangent()), a, b, row.yaw());
                        break;
                    }
                }
            }
            boolean onMain = row.mainDistance() - 0.5 * scale < main.length();
            boolean onBranch = row.branchDistance() - 0.5 * scale < branch.length();
            if (onMain) fixed(parts, kind + (kind.endsWith("01") ? "" : side > 0 ? "R" : "L"), row.main(),
                    onBranch ? row.yaw() : yaw(main.at(row.mainDistance())));
            if (onBranch) fixed(parts, kind + (kind.endsWith("01") ? "" : side > 0 ? "L" : "R"), row.branch(),
                    onMain ? row.yaw() : yaw(branch.at(row.branchDistance())));
        }
        if (normal == null || bladeRow == null || bladeRow.mainDistance() >= toe) throw new InvalidLayout(Problem.SPACE);
        var mainTip = edgeOnRow(branch, bladeRow, side * (RAIL_X - HEAD_HALF) * scale);
        var branchTip = edgeOnRow(main, bladeRow, -side * (RAIL_X - HEAD_HALF) * scale);
        double mainStart = station(main, mainTip.point(), main.at(bladeRow.mainDistance()).tangent());
        double branchStart = station(branch, branchTip.point(), branch.at(bladeRow.branchDistance()).tangent());
        rails(parts, main, branch, crossing.main(), toe, side, false, mainFrog, branch.edge(branchToe, side * STOCK_WORK_X * scale),
                mainStart, mainTip, normal.mainDistance(), normal.main(), scale);
        rails(parts, branch, main, crossing.branch(), branchToe, -side, true, branchFrog, main.edge(toe, -side * STOCK_WORK_X * scale),
                branchStart, branchTip, normal.branchDistance(), normal.branch(), scale);
        for (var row : rows) bed(parts, main, branch, row, side, scale);
        return new NativeTurnoutLayout(main.at(0).position(), scale, new ArrayList<>(parts),
                main.poses.size() + branch.poses.size());
    }

    private static Edge edgeOnRow(Path path, RowFrame row, double offset) {
        double lo = 0, hi = path.length();
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2; var p = path.edge(mid, offset);
            double along = (p.x() - row.main().x()) * Math.sin(row.yaw()) + (p.z() - row.main().z()) * Math.cos(row.yaw());
            if (along < 0) lo = mid; else hi = mid;
        }
        double s = (lo + hi) / 2;
        return new Edge(s, path.edge(s, offset));
    }

    private static RowFrame row(Path main, Path branch, double s, int index, double scale) {
        var a = main.extended(s);
        double range = main.length() + branch.length(), lo = -range, hi = branch.length() + range;
        for (int iteration = 0; iteration < 40; iteration++) {
            double mid = (lo + hi) / 2; var b = branch.extended(mid);
            double along = (b.position().x() - a.position().x()) * (a.tangent().x() + b.tangent().x())
                    + (b.position().z() - a.position().z()) * (a.tangent().z() + b.tangent().z());
            if (along < 0) lo = mid; else hi = mid;
        }
        double other = (lo + hi) / 2; var b = branch.extended(other);
        if (a.tangent().x() * b.tangent().x() + a.tangent().z() * b.tangent().z() < 0.5) throw new InvalidLayout(Problem.SPACE);
        double direction = yaw(a) + wrap(yaw(b) - yaw(a)) / 2;
        double across = (b.position().x() - a.position().x()) * Math.cos(direction)
                - (b.position().z() - a.position().z()) * Math.sin(direction);
        var paired = shifted(a.position(), direction, across, 0);
        if (distance(paired, b.position()) > 1e-5 * scale) throw new InvalidLayout(Problem.SPACE);
        return new RowFrame(index, s, other, a.position(), paired, direction);
    }

    private static void bed(LinkedHashSet<Part> parts, Path main, Path branch, RowFrame row, double side, double scale) {
        boolean hasMain = row.mainDistance() < main.length() + 0.32 * scale;
        boolean hasBranch = row.branchDistance() >= -1e-6 * scale && row.branchDistance() < branch.length() + 0.32 * scale;
        String name = "ballast0" + (row.index() % 4 + 1);
        double mainYaw = yaw(main.at(row.mainDistance())), branchYaw = yaw(branch.at(row.branchDistance()));
        boolean coincident = distance(row.main(), row.branch()) < 1e-6 * scale && Math.abs(wrap(mainYaw - branchYaw)) < 1e-6;
        var midpoint = new TrackVector((row.main().x() + row.branch().x()) / 2, row.main().y(),
                (row.main().z() + row.branch().z()) / 2);
        if (hasMain) parts.add(new Part(name, row.main(), mainYaw, 0, 0, 0, 0, 0, null, null,
                hasBranch && !coincident ? bedCut(row.main(), mainYaw, midpoint, row.yaw(), -side, scale) : null, null));
        if (hasBranch && !coincident) parts.add(new Part(name, row.branch(), branchYaw, 0, 0, 0, 0, 0, null, null,
                hasMain ? bedCut(row.branch(), branchYaw, midpoint, row.yaw(), side, scale) : null, null));
    }
    private static BedCut bedCut(TrackVector origin, double direction, TrackVector midpoint, double rowYaw, double side, double scale) {
        double offset = ((origin.x() - midpoint.x()) * Math.cos(rowYaw) - (origin.z() - midpoint.z()) * Math.sin(rowYaw)) / scale;
        return new BedCut(side * Math.cos(direction - rowYaw), side * Math.sin(direction - rowYaw), side * offset);
    }

    private static TrackVector wing(LinkedHashSet<Part> parts, String name, TrackVector origin,
                                    double front, double rear, double localX, double scale) {
        fixed(parts, name + "f", origin, front);
        var joint = shifted(origin, front, localX * scale, 0);
        var rearOrigin = shifted(joint, rear, -localX * scale, 0);
        fixed(parts, name + "r", rearOrigin, rear);
        return shifted(joint, rear, 0, WING_REAR * scale);
    }

    private static void rails(LinkedHashSet<Part> parts, Path path, Path stock, double frog, double toe,
                              double inner, boolean reverse, Frog ports, TrackVector oldTip,
                              double newToe, Edge contactTip, double normalStart, TrackVector normalPoint, double scale) {
        var tip = contactTip.point();
        double incoming = station(path, ports.incoming(), path.at(frog).tangent());
        double outgoing = station(path, ports.outgoing(), path.at(frog).tangent());
        double root = frog * (1 - 0.4 * frog / path.length());
        double bladeLength = (BLADE_JOIN - BLADE_TIP) * scale;
        if (root - toe < bladeLength + 0.5 * scale || root >= incoming) throw new InvalidLayout(Problem.SPACE);
        double lo = toe, hi = root;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2;
            if (distance(oldTip, path.edge(mid, inner * RAIL_X * scale)) < bladeLength) lo = mid; else hi = mid;
        }
        double bladeEnd = (lo + hi) / 2;
        double bladeSpan = distance(tip, path.edge(bladeEnd, inner * RAIL_X * scale));
        double stockOffset = inner * (RAIL_X - HEAD_HALF) * scale;
        double stockYaw = yaw(stock.at(contactTip.distance())), support = 0;
        for (int i = 1; i <= 16; i++) {
            var point = stock.edge(contactTip.distance() + bladeSpan * i / 16, stockOffset);
            support = Math.min(support, inner * wrap(frame(tip, point).yaw() - stockYaw));
        }
        double contactYaw = stockYaw + inner * support - inner * Math.atan2(HEAD_HALF * scale, bladeSpan * 0.65);
        double previousYaw = frame(tip, path.edge(bladeEnd, inner * RAIL_X * scale)).yaw();
        double bladeYaw = !reverse && inner * wrap(previousYaw - contactYaw) <= 0 ? previousYaw : contactYaw;
        double stockLo = contactTip.distance(), stockHi = stock.length();
        for (int i = 0; i < 40; i++) {
            double s = (stockLo + stockHi) / 2; var point = stock.edge(s, stockOffset);
            double forward = (point.x() - tip.x()) * Math.sin(bladeYaw) + (point.z() - tip.z()) * Math.cos(bladeYaw);
            if (forward < bladeSpan) stockLo = s; else stockHi = s;
        }
        double stockEnd = (stockLo + stockHi) / 2;
        var rear = shifted(tip, bladeYaw, 0, bladeSpan);
        var contactEdges = new ArrayList<BladeEdge>();
        int contactCount = Math.max(1, (int)Math.ceil(bladeSpan / (0.2 * scale)));
        for (int i = 0; i <= contactCount; i++) {
            double along = bladeSpan * i / contactCount;
            double low = contactTip.distance(), high = stockEnd;
            for (int j = 0; j < 40; j++) {
                double s = (low + high) / 2; var point = stock.edge(s, stockOffset);
                double forward = (point.x() - tip.x()) * Math.sin(bladeYaw) + (point.z() - tip.z()) * Math.cos(bladeYaw);
                if (forward < along) low = s; else high = s;
            }
            var point = stock.edge((low + high) / 2, stockOffset);
            double across = (point.x() - tip.x()) * Math.cos(bladeYaw) - (point.z() - tip.z()) * Math.sin(bladeYaw);
            contactEdges.add(new BladeEdge((float)(along / scale), i == 0 ? 0 : (float)(across / scale)));
        }
        var bladeContact = new BladeContact(contactEdges);
        var anchor = path.edge(root, inner * RAIL_X * scale);
        int fixedCount = Math.max(1, (int)Math.ceil((incoming - root) / (RAIL_STEP * scale)));
        var fixedNext = fixedCount == 1 ? ports.incoming()
                : path.edge(root + (incoming - root) / fixedCount, inner * RAIL_X * scale);
        double fixedYaw = frame(anchor, fixedNext).yaw();
        var blade = frame(tip, rear);
        var curve = transition(tip, rear, anchor, blade.yaw(), fixedYaw, scale);
        var joints = curve.points();
        var frames = new ArrayList<Frame>();
        var weights = new ArrayList<Double>();
        double closureLength = distance(rear, anchor);
        for (int i = 0; i + 1 < joints.size(); i++) {
            frames.add(frame(joints.get(i), joints.get(i + 1)));
            double t = Math.clamp(distance(rear, joints.get(i)) / closureLength, 0, 1);
            weights.add(i <= 1 ? 1.0 : i == joints.size() - 2 ? 0.0 : 1 - t * t * (3 - 2 * t));
        }
        // Solve one angular amplitude for the requested toe opening. Endpoint links share
        // their angles with the blade / fixed rail at every intermediate animation value.
        var lateral = path.at(newToe).lateral();
        double lowAngle = 0, highAngle = 0.15;
        for (int i = 0; i < 32; i++) {
            double angle = (lowAngle + highAngle) / 2;
            var opened = chainStart(anchor, frames, weights, inner * angle);
            double opening = -inner * ((opened.x() - tip.x()) * lateral.x() + (opened.z() - tip.z()) * lateral.z());
            if (opening < BLADE_OPENING * scale) lowAngle = angle; else highAngle = angle;
        }
        double travel = inner * (lowAngle + highAngle) / 2;
        Motion next = null;
        var moving = new ArrayList<Part>();
        for (int i = joints.size() - 2; i >= 0; i--) {
            var a = joints.get(i); var b = joints.get(i + 1);
            var frame = frames.get(i);
            if (i > 0 && frame.length() > 1.58 * scale) throw new InvalidLayout(Problem.SPACE);
            next = new Motion(next, b, frame.length(), frame.yaw(), frame.pitch(), travel * weights.get(i), reverse);
            float derivedLength = i == 0 ? (float)(frame.length() / scale) : 0;
            double localZ = i == 0 ? BLADE_JOIN - derivedLength : -frame.length() / (2 * scale);
            double startYaw = curve.tangents().get(i), endYaw = curve.tangents().get(i + 1);
            moving.add(new Part(i == 0 ? (inner > 0 ? "L1" : "R1") : (inner > 0 ? "railL" : "railR"),
                    a, frame.yaw(), frame.pitch(), inner * RAIL_X, localZ,
                    i == 0 ? 0 : (float)(frame.length() / scale), derivedLength,
                    i == 0 ? bladeContact : null, i == 0 ? null : new RailBend((float)wrap(startYaw - frame.yaw()),
                            (float)wrap(endYaw - frame.yaw())), null, next));
        }
        for (var part : moving.reversed()) parts.add(part);

        // A single integer grid owns normal sleepers and both of their embedded rails.
        double step = 1.5 * scale;
        TrackVector innerEnd = path.edge(path.length(), inner * RAIL_X * scale);
        TrackVector outerEnd = path.edge(path.length(), -inner * RAIL_X * scale);
        double boundary = path.length();
        if (normalStart < path.length()) {
            var firstFrame = new Frame(normalPoint, normalPoint, yaw(path.at(normalStart)), 0, 0);
            innerEnd = localPoint(firstFrame, inner * RAIL_X * scale, -0.75 * scale);
            outerEnd = localPoint(firstFrame, -inner * RAIL_X * scale, -0.75 * scale);
            boundary = station(path, innerEnd, path.at(normalStart).tangent());
            int last = (int)Math.ceil((path.length() - normalStart) / step) - 1;
            parts.add(new Part("sleeper", normalPoint, firstFrame.yaw(), 0, 0, 0, 0, 0, null, null, null, null));
            for (int index = 1; index <= last; index++) ordinary(parts, path, normalStart + index * step, scale);
            double lastCenter = normalStart + last * step;
            double tail = lastCenter + 0.75 * scale;
            if (tail < path.length()) {
                var lastFrame = last == 0 ? firstFrame : centerFrame(path, lastCenter, scale);
                railRun(parts, path, tail, path.length(), localPoint(lastFrame, inner * RAIL_X * scale, 0.75 * scale),
                        path.edge(path.length(), inner * RAIL_X * scale), inner, scale);
                railRun(parts, path, tail, path.length(), localPoint(lastFrame, -inner * RAIL_X * scale, 0.75 * scale),
                        path.edge(path.length(), -inner * RAIL_X * scale), -inner, scale);
            }
        }
        railRun(parts, path, 0, boundary, path.edge(0, -inner * RAIL_X * scale), outerEnd, -inner, scale);
        railRun(parts, path, root, incoming, anchor, ports.incoming(), inner, scale);
        railRun(parts, path, outgoing, boundary, ports.outgoing(), innerEnd, inner, scale);
    }

    private static Transition transition(TrackVector tip, TrackVector rear, TrackVector root,
                                         double bladeYaw, double fixedYaw, double scale) {
        double length = distance(rear, root), handle = length / 3;
        var a = shifted(rear, bladeYaw, 0, handle);
        var b = shifted(root, fixedYaw, 0, -handle);
        var result = new ArrayList<TrackVector>(); result.add(tip); result.add(rear);
        var tangents = new ArrayList<Double>(); tangents.add(bladeYaw); tangents.add(bladeYaw);
        int count = Math.max(1, (int)Math.ceil(length / (0.5 * scale)));
        for (int i = 1; i < count; i++) {
            double t = (double)i / count, u = 1 - t;
            result.add(new TrackVector(u*u*u*rear.x() + 3*u*u*t*a.x() + 3*u*t*t*b.x() + t*t*t*root.x(),
                    u*u*u*rear.y() + 3*u*u*t*a.y() + 3*u*t*t*b.y() + t*t*t*root.y(),
                    u*u*u*rear.z() + 3*u*u*t*a.z() + 3*u*t*t*b.z() + t*t*t*root.z()));
            tangents.add(Math.atan2(u*u*(a.x() - rear.x()) + 2*u*t*(b.x() - a.x()) + t*t*(root.x() - b.x()),
                    u*u*(a.z() - rear.z()) + 2*u*t*(b.z() - a.z()) + t*t*(root.z() - b.z())));
        }
        result.add(root); tangents.add(fixedYaw); return new Transition(result, tangents);
    }

    private static TrackVector chainStart(TrackVector root, List<Frame> frames, List<Double> weights, double travel) {
        var point = root;
        for (int i = frames.size() - 1; i >= 0; i--) {
            var frame = frames.get(i); double angle = frame.yaw() + travel * weights.get(i);
            double horizontal = frame.length() * Math.cos(frame.pitch());
            point = new TrackVector(point.x() - Math.sin(angle) * horizontal, point.y() - (frame.end().y() - frame.start().y()),
                    point.z() - Math.cos(angle) * horizontal);
        }
        return point;
    }

    private static void railRun(LinkedHashSet<Part> parts, Path path, double a, double b,
                                TrackVector start, TrackVector end, double side, double scale) {
        if (b < a) throw new InvalidLayout(Problem.SPACE);
        if (distance(start, end) < 1e-6 * scale) return;
        int count = Math.max(1, (int)Math.ceil((b - a) / (RAIL_STEP * scale)));
        var points = new ArrayList<TrackVector>(); points.add(start);
        for (int index = 1; index <= count; index++) points.add(index == count ? end
                : path.edge(a + (b - a) * index / count, side * RAIL_X * scale));
        var frames = new ArrayList<Frame>();
        for (int i = 0; i < count; i++) frames.add(frame(points.get(i), points.get(i + 1)));
        var previous = start;
        for (int index = 1; index <= count; index++) {
            var point = points.get(index); var frame = frames.get(index - 1);
            if (frame.length() > 1.58 * scale) throw new InvalidLayout(Problem.SPACE);
            double first = index == 1 ? frame.yaw() : frames.get(index - 2).yaw()
                    + wrap(frame.yaw() - frames.get(index - 2).yaw()) / 2;
            double last = index == count ? frame.yaw() : frame.yaw() + wrap(frames.get(index).yaw() - frame.yaw()) / 2;
            parts.add(new Part(side > 0 ? "railL" : "railR", previous, frame.yaw(), frame.pitch(),
                    side * RAIL_X, -frame.length() / (2 * scale), frame.length() / scale, 0, null,
                    new RailBend((float)wrap(first - frame.yaw()), (float)wrap(last - frame.yaw())), null, null));
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
        parts.add(new Part("sleeper", localPoint(f, 0, 0), f.yaw(), f.pitch(), 0, 0, 0, 0, null, null, null, null));
    }
    private static double distance(TrackVector a, TrackVector b) {
        return Math.hypot(Math.hypot(b.x() - a.x(), b.z() - a.z()), b.y() - a.y());
    }
    private static double direction(Path path, double a, double b) {
        return frame(path.at(a).position(), path.at(b).position()).yaw();
    }
    private static void fixed(LinkedHashSet<Part> parts, String name, TrackVector p, double yaw) {
        parts.add(new Part(name, p, yaw, 0, 0, 0, 0, 0, null, null, null, null));
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
