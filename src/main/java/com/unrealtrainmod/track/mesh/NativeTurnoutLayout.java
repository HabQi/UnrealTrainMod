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
import java.util.BitSet;
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
    private static final double CONNECTION_STEP = 0.5;
    private static final double MOTION_JOINT_ANGLE = Math.toRadians(0.1);
    private static final double SOLVE_TOLERANCE = 0.00001;

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
            if (motion != null) return motion.transform(anchor, scale, movement);
            return new Matrix4f().translation((float)(position.x() - anchor.x()),
                    (float)(position.y() - anchor.y()), (float)(position.z() - anchor.z()))
                    .rotateY((float)yaw).rotateX((float)-pitch)
                    .translate((float)(-localX * scale), 0, (float)(-localZ * scale)).scale((float)scale);
        }
    }

    /** Connected rigid links evaluated from the fixed root toward the tip. No geometry/path access. */
    public static final class Motion {
        private final Motion next;
        private final TrackVector root;
        private final double horizontal, rise, yaw, travel;
        private final double pitchSine, pitchCosine, offsetX, offsetY, offsetZ;
        private final boolean reverse;
        private float cachedMovement = Float.NaN;
        private TrackVector cachedStart;
        private double cachedSine, cachedCosine;

        private Motion(Motion next, TrackVector end, double length, double yaw, double pitch,
                       double travel, boolean reverse, double localX, double localZ) {
            this.next = next; root = end;
            pitchSine = Math.sin(pitch); pitchCosine = Math.cos(pitch);
            horizontal = length * pitchCosine; rise = length * pitchSine;
            offsetX = -localX; offsetY = -localZ * pitchSine; offsetZ = -localZ * pitchCosine;
            this.yaw = yaw; this.travel = travel; this.reverse = reverse;
        }
        public double direction(float movement) { return yaw + travel * (reverse ? 1 - movement : movement); }
        public TrackVector start(float movement) {
            if (cachedMovement != movement) {
                var end = next == null ? root : next.start(movement);
                double angle = direction(movement);
                cachedSine = Math.sin(angle); cachedCosine = Math.cos(angle);
                cachedStart = new TrackVector(end.x() - cachedSine * horizontal,
                        end.y() - rise, end.z() - cachedCosine * horizontal);
                cachedMovement = movement;
            }
            return cachedStart;
        }
        private Matrix4f transform(TrackVector anchor, double scale, float movement) {
            var point = start(movement);
            double sine = cachedSine, cosine = cachedCosine;
            // Ry(yaw) * Rx(-pitch) * scale, sharing yaw trig with the endpoint calculation.
            return new Matrix4f().set(
                    (float)(cosine * scale), 0, (float)(-sine * scale), 0,
                    (float)(-sine * pitchSine * scale), (float)(pitchCosine * scale), (float)(-cosine * pitchSine * scale), 0,
                    (float)(sine * pitchCosine * scale), (float)(pitchSine * scale), (float)(cosine * pitchCosine * scale), 0,
                    (float)(point.x() - anchor.x() + scale * (cosine * offsetX + sine * offsetZ)),
                    (float)(point.y() - anchor.y() + scale * offsetY),
                    (float)(point.z() - anchor.z() + scale * (-sine * offsetX + cosine * offsetZ)), 1);
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
    /** Constructor compatibility only; native layouts leave this unused descriptor null. */
    public record BedCut(double x, double z, double offset) {}
    public record BladeEdge(float distance, float offset) {}
    public record BladeContact(List<BladeEdge> edges, NativeRailCurve curve, double side, double tipHeight) {
        public BladeContact(List<BladeEdge> edges) { this(edges, null, 0, 0); }
        public BladeContact { edges = List.copyOf(edges); }
    }
    public record RailBend(float start, float end, NativeRailCurve curve) {
        public RailBend(float start, float end) { this(start, end, null); }
    }
    public record RowFrame(int index, double mainDistance, double branchDistance,
                           TrackVector main, TrackVector branch, double yaw) {}
    private record Edge(double distance, TrackVector point) {}
    private record Crossing(double main, double branch, TrackVector point) {}
    private record Frame(TrackVector start, TrackVector end, double yaw, double pitch, double length) {}
    private record Transition(List<Frame> frames, List<Double> tangents, List<Double> weights, double travel, List<CurvePoint> nodes) {}
    private record CurvePoint(double distance, TrackVector position, double tangent) {}
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
        var mainTip = edgeOnRow(branch, bladeRow, side * (RAIL_X - HEAD_HALF) * scale, scale);
        var branchTip = edgeOnRow(main, bladeRow, -side * (RAIL_X - HEAD_HALF) * scale, scale);
        rails(parts, main, branch, crossing.main(), toe, side, false, mainFrog, branch.edge(branchToe, side * STOCK_WORK_X * scale),
                mainTip, normal.mainDistance(), normal.main(), scale);
        rails(parts, branch, main, crossing.branch(), branchToe, -side, true, branchFrog, main.edge(toe, -side * STOCK_WORK_X * scale),
                branchTip, normal.branchDistance(), normal.branch(), scale);
        for (var row : rows) bed(parts, main, branch, row, scale);
        return new NativeTurnoutLayout(main.at(0).position(), scale, new ArrayList<>(parts),
                main.poses.size() + branch.poses.size());
    }

    private static Edge edgeOnRow(Path path, RowFrame row, double offset, double scale) {
        double lo = 0, hi = path.length();
        double sine = Math.sin(row.yaw()), cosine = Math.cos(row.yaw());
        while (hi - lo > SOLVE_TOLERANCE * scale) {
            double mid = (lo + hi) / 2; var p = path.edge(mid, offset);
            double along = (p.x() - row.main().x()) * sine + (p.z() - row.main().z()) * cosine;
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

    private static void bed(LinkedHashSet<Part> parts, Path main, Path branch, RowFrame row, double scale) {
        boolean hasMain = row.mainDistance() < main.length() + 0.32 * scale;
        boolean hasBranch = row.branchDistance() >= -1e-6 * scale && row.branchDistance() < branch.length() + 0.32 * scale;
        String name = "ballast0" + (row.index() % 4 + 1);
        double mainYaw = yaw(main.at(row.mainDistance())), branchYaw = yaw(branch.at(row.branchDistance()));
        boolean coincident = distance(row.main(), row.branch()) < 1e-6 * scale && Math.abs(wrap(mainYaw - branchYaw)) < 1e-6;
        if (hasMain) parts.add(new Part(name, row.main(), mainYaw, 0, 0, 0, 0, 0, null, null, null, null));
        if (hasBranch && !coincident) parts.add(new Part(name, row.branch(), branchYaw, 0, 0, 0, 0, 0, null, null, null, null));
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
                              Edge contactTip, double normalStart, TrackVector normalPoint, double scale) {
        var referenceTip = contactTip.point();
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
        double bladeSpan = distance(referenceTip, path.edge(bladeEnd, inner * RAIL_X * scale));
        double tolerance = SOLVE_TOLERANCE * scale, railOffset = inner * RAIL_X * scale;
        // The former shortened layout defines only the heel anchor, not the final blade length.
        double referenceTipStation = contactStation(path, railOffset, referenceTip, 0, root, tolerance);
        var referenceSpineTip = path.edge(referenceTipStation, railOffset);
        double heelStation = chordStation(path, railOffset, referenceSpineTip, bladeSpan * (2.0 / 3), referenceTipStation, root, tolerance);
        var rear = path.edge(heelStation, railOffset);
        double tipStation = backwardChordStation(path, railOffset, rear, bladeSpan, 0, referenceTipStation, tolerance);
        var spineTip = path.edge(tipStation, railOffset);
        double stockOffset = inner * (RAIL_X - HEAD_HALF) * scale;
        double stockTipStation = projectedStation(stock, stockOffset, spineTip, spineKnot(path, railOffset, tipStation, scale).first(),
                0, stock.length(), tolerance);
        var tip = stock.edge(stockTipStation, stockOffset);
        double connectorHigh = root - CONNECTION_STEP * scale;
        double connectorStation = chordStation(path, railOffset, rear, RAIL_STEP * scale,
                heelStation, connectorHigh, tolerance);
        var anchor = path.edge(root, railOffset);
        double fixedYaw = yaw(path.at(root));
        var tipOffset = NativeRailCurve.add(tip, spineTip, -1);
        var curve = transition(path, tipStation, heelStation, connectorStation, root,
                path.at(tipStation).lateral(), tipOffset, inner, scale);
        var boundaries = new ArrayList<Double>();
        boundaries.add(tipStation); boundaries.add(heelStation);
        for (var node : curve.nodes()) boundaries.add(node.distance());
        var fittingBoundaries = new ArrayList<Double>(); fittingBoundaries.add(tipStation);
        for (int i = 1; i < boundaries.size(); i++) {
            double a = boundaries.get(i-1), b = boundaries.get(i);
            int count = Math.max(1, (int)Math.ceil((b-a)/(0.1*scale)));
            for (int j = 1; j <= count; j++) fittingBoundaries.add(j == count ? b : a+(b-a)*j/count);
        }
        var spine = NativeRailCurve.fit(s -> spineKnot(path, railOffset, s, scale), fittingBoundaries, tolerance);
        var bladeFrame = curve.frames().getFirst();
        var bladeCurve = spine.local(tipStation, heelStation, spineTip, bladeFrame.yaw(), bladeFrame.pitch(), scale, bladeFrame.length() / scale);
        var contactEdges = new ArrayList<BladeEdge>();
        double search = Math.max(0, stockTipStation - tolerance), firstContact = 0;
        int contactCount = Math.max(2, (int)Math.ceil(bladeSpan / (0.1 * scale)));
        for (int i = 0; i <= contactCount; i++) {
            double s = tipStation + (heelStation - tipStation) * i / contactCount;
            var sample = spine.at(s); var center = sample.point(); var pose = path.at(s);
            double stockStation = i == 0 ? stockTipStation : projectedStation(stock, stockOffset, center, sample.first(),
                    search, stock.length(), tolerance);
            search = Math.max(0, stockStation - tolerance);
            var edge = i == 0 ? tip : stock.edge(stockStation, stockOffset);
            double across = ((edge.x()-center.x())*pose.lateral().x() + (edge.z()-center.z())*pose.lateral().z()) / scale;
            if (i == 0) firstContact = across;
            double t = (double)i/contactCount, smooth = t*t*t*(10+t*(-15+6*t));
            double lower = -HEAD_HALF + (inner*firstContact+HEAD_HALF)*(1-smooth);
            if (inner*across < lower-tolerance/scale) throw new InvalidLayout(Problem.SPACE);
            contactEdges.add(new BladeEdge((float)(bladeFrame.length() / scale * i / contactCount), (float)across));
        }
        if (Math.abs(contactEdges.getFirst().offset()) >= HEAD_HALF
                || inner * contactEdges.getLast().offset() < 1.2 * HEAD_HALF) throw new InvalidLayout(Problem.SPACE);
        var tipNormal = NativeRailCurve.cross(spine.at(tipStation).first(), path.at(tipStation).lateral());
        double tipHeight = NativeRailCurve.dot(tipOffset, tipNormal) / Math.sqrt(NativeRailCurve.dot(tipNormal, tipNormal)) / scale;
        var bladeContact = new BladeContact(contactEdges, bladeCurve, inner, tipHeight);
        Motion next = null;
        var chainParts = new ArrayList<Part>();
        for (int i = curve.frames().size() - 1; i >= 0; i--) {
            var frame = curve.frames().get(i);
            if (i > 0 && frame.length() > 1.58 * scale) throw new InvalidLayout(Problem.SPACE);
            float derivedLength = i == 0 ? (float)(frame.length() / scale) : 0;
            double localZ = i == 0 ? BLADE_JOIN - derivedLength : -frame.length() / (2 * scale);
            double travel = curve.travel() * curve.weights().get(i);
            Motion motion = null;
            // A zero-rotation suffix is fixed; the remaining chain anchors to its start.
            if (next != null || travel != 0) {
                next = new Motion(next, frame.end(), frame.length(), frame.yaw(), frame.pitch(), travel, reverse, inner * RAIL_X, localZ);
                motion = next;
            }
            double startYaw = curve.tangents().get(i), endYaw = curve.tangents().get(i + 1);
            double from = boundaries.get(i), to = boundaries.get(i + 1);
            var localCurve = i == 0 ? bladeCurve : spine.local(from, to, frame.start(), frame.yaw(), frame.pitch(), scale, frame.length() / scale);
            chainParts.add(new Part(i == 0 ? (inner > 0 ? "L1" : "R1") : (inner > 0 ? "railL" : "railR"),
                    frame.start(), frame.yaw(), frame.pitch(), inner * RAIL_X, localZ,
                    i == 0 ? 0 : (float)(frame.length() / scale), derivedLength,
                    i == 0 ? bladeContact : null, i == 0 ? null : new RailBend((float)wrap(startYaw - frame.yaw()),
                            (float)wrap(endYaw - frame.yaw()), localCurve), null, motion));
        }
        for (var part : chainParts.reversed()) parts.add(part);

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
                        path.edge(path.length(), inner * RAIL_X * scale), inner, scale, lastFrame.yaw());
                railRun(parts, path, tail, path.length(), localPoint(lastFrame, -inner * RAIL_X * scale, 0.75 * scale),
                        path.edge(path.length(), -inner * RAIL_X * scale), -inner, scale, lastFrame.yaw());
            }
        }
        railRun(parts, path, 0, boundary, path.edge(0, -inner * RAIL_X * scale), outerEnd, -inner, scale, yaw(path.at(0)));
        railRun(parts, path, root, incoming, anchor, ports.incoming(), inner, scale, fixedYaw);
        railRun(parts, path, outgoing, boundary, ports.outgoing(), innerEnd, inner, scale,
                direction(path, frog, frog + 2 * scale));
    }

    private static double projectedStation(Path path, double offset, TrackVector origin, TrackVector direction,
                                           double low, double high, double tolerance) {
        if (projection(path.edge(low, offset), origin, direction) > 0
                || projection(path.edge(high, offset), origin, direction) < 0) throw new InvalidLayout(Problem.SPACE);
        while (high - low > tolerance) {
            double mid = (low + high) / 2;
            if (projection(path.edge(mid, offset), origin, direction) < 0) low = mid; else high = mid;
        }
        return (low + high) / 2;
    }

    private static double projection(TrackVector point, TrackVector origin, TrackVector direction) {
        return (point.x()-origin.x())*direction.x() + (point.y()-origin.y())*direction.y() + (point.z()-origin.z())*direction.z();
    }

    private static double contactStation(Path path, double offset, TrackVector point, double low, double high, double tolerance) {
        while (high - low > tolerance) {
            double mid = (low + high) / 2; var delta = NativeRailCurve.add(path.edge(mid, offset), point, -1);
            var pose = path.at(mid); var tangent = pose.tangent();
            double factor = 1-offset*pose.curvature();
            double forward = (delta.x()*tangent.x()+delta.z()*tangent.z())*factor + delta.y()*tangent.y();
            if (forward < 0) low = mid; else high = mid;
        }
        return (low + high) / 2;
    }

    private static double chordStation(Path path, double offset, TrackVector point, double length,
                                       double low, double high, double tolerance) {
        if (high <= low || distance(point, path.edge(high, offset)) < length) throw new InvalidLayout(Problem.SPACE);
        while (high - low > tolerance) {
            double mid = (low + high) / 2;
            if (distance(point, path.edge(mid, offset)) < length) low = mid; else high = mid;
        }
        return (low + high) / 2;
    }

    private static double backwardChordStation(Path path, double offset, TrackVector point, double length,
                                               double low, double high, double tolerance) {
        if (high <= low || distance(point, path.edge(low, offset)) < length) throw new InvalidLayout(Problem.SPACE);
        while (high - low > tolerance) {
            double mid = (low + high) / 2;
            if (distance(point, path.edge(mid, offset)) > length) low = mid; else high = mid;
        }
        return (low + high) / 2;
    }

    private static NativeRailCurve.Knot spineKnot(Path path, double offset, double s, double scale) {
        var pose = path.at(s); var t = pose.tangent(); var lateral = pose.lateral();
        double horizontal = Math.hypot(t.x(), t.z()), curvature = pose.curvature();
        double factor = 1 - offset * curvature;
        double lo = Math.max(0, s - 0.001 * scale), hi = Math.min(path.length(), s + 0.001 * scale);
        double curvatureDerivative = (path.at(hi).curvature() - path.at(lo).curvature()) / (hi - lo);
        var first = new TrackVector(t.x()*factor, t.y(), t.z()*factor);
        double normal = curvature * horizontal * horizontal * factor;
        var second = new TrackVector(lateral.x()*normal-t.x()*offset*curvatureDerivative, 0,
                lateral.z()*normal-t.z()*offset*curvatureDerivative);
        return new NativeRailCurve.Knot(s, path.edge(s, offset), first, second);
    }

    private static Transition transition(Path path, double tipStation, double heelStation, double startDistance, double root,
                                         TrackVector lateral, TrackVector tipOffset, double inner, double scale) {
        double length = root - startDistance, offset = inner * RAIL_X * scale;
        int count = Math.max(3, (int)Math.ceil(length / (CONNECTION_STEP * scale)));
        var nodes = new ArrayList<CurvePoint>(count + 1);
        for (int i = 0; i <= count; i++) nodes.add(curvePoint(path, offset,
                i == count ? root : startDistance + length * i / count));
        var tip = path.edge(tipStation, offset); var rear = path.edge(heelStation, offset);
        var frames = new ArrayList<Frame>(count + 2);
        frames.add(frame(tip, rear));
        frames.add(frame(rear, nodes.getFirst().position()));
        for (int i = 0; i < count; i++) frames.add(frame(nodes.get(i).position(), nodes.get(i + 1).position()));
        while (true) {
            var weights = motionWeights(frames);
            double travel = solveTravel(frames, weights, lateral, tipOffset, inner, scale);
            var split = new BitSet(nodes.size() - 1);
            // Closed-pose end tangents match. Their full-stroke rotation difference bounds
            // the tangent discontinuity at every intermediate pose, in both switch directions.
            for (int i = 3; i < frames.size(); i++) {
                if (Math.abs(travel * (weights.get(i) - weights.get(i - 1))) <= MOTION_JOINT_ANGLE) continue;
                int link = frames.get(i - 1).length() >= frames.get(i).length() ? i - 1 : i;
                split.set(link - 2);
            }
            if (split.isEmpty()) {
                var tangents = new ArrayList<Double>(nodes.size() + 2);
                tangents.add(yaw(path.at(tipStation))); tangents.add(yaw(path.at(heelStation)));
                for (var node : nodes) tangents.add(node.tangent());
                return new Transition(frames, tangents, weights, travel, nodes);
            }
            var nextNodes = new ArrayList<CurvePoint>(nodes.size() + split.cardinality());
            var nextFrames = new ArrayList<Frame>(frames.size() + split.cardinality());
            nextNodes.add(nodes.getFirst());
            nextFrames.add(frames.get(0)); nextFrames.add(frames.get(1));
            for (int i = 0; i + 1 < nodes.size(); i++) {
                var start = nodes.get(i); var end = nodes.get(i + 1);
                if (split.get(i)) {
                    var mid = curvePoint(path, offset, (start.distance() + end.distance()) / 2);
                    nextNodes.add(mid);
                    nextFrames.add(frame(start.position(), mid.position()));
                    nextFrames.add(frame(mid.position(), end.position()));
                } else nextFrames.add(frames.get(i + 2));
                nextNodes.add(end);
            }
            nodes = nextNodes; frames = nextFrames;
        }
    }

    private static CurvePoint curvePoint(Path path, double offset, double distance) {
        var pose = path.at(distance); var point = pose.position(); var lateral = pose.lateral();
        var position = new TrackVector(point.x() + lateral.x() * offset, point.y(), point.z() + lateral.z() * offset);
        return new CurvePoint(distance, position, yaw(pose));
    }

    private static List<Double> motionWeights(List<Frame> frames) {
        // The blade and added ordinary rail precede the deforming connection curve.
        double length = 0;
        for (int i = 2; i < frames.size(); i++) length += frames.get(i).length();
        double first = frames.get(2).length() / 2, last = length - frames.getLast().length() / 2;
        double inverseRange = 1 / (last - first);
        var weights = new ArrayList<Double>(frames.size()); weights.add(1.0); weights.add(1.0);
        double along = 0;
        for (int i = 2; i < frames.size(); i++) {
            var frame = frames.get(i);
            double t = Math.clamp((along + frame.length() / 2 - first) * inverseRange, 0, 1);
            // Quintic falloff has zero first and second derivatives at both ends. Using
            // link centers distributes rotation along the chain without a forced last-link jump.
            weights.add(i == 2 ? 1.0 : i == frames.size() - 1 ? 0.0 : 1 - t*t*t*(10 + t*(-15 + 6*t)));
            along += frame.length();
        }
        return weights;
    }

    private static double solveTravel(List<Frame> frames, List<Double> weights, TrackVector lateral, TrackVector tipOffset, double inner, double scale) {
        // Per link: closed projection, its yaw derivative, and signed rotation weight.
        var coefficients = new double[(frames.size() + 1) * 3];
        double derivativeBound = 0, lateralLength = Math.hypot(lateral.x(), lateral.z());
        for (int i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            double horizontal = frame.length() * Math.cos(frame.pitch());
            double sine = Math.sin(frame.yaw()), cosine = Math.cos(frame.yaw()), weight = weights.get(i);
            coefficients[i * 3] = horizontal * (lateral.x() * sine + lateral.z() * cosine);
            coefficients[i * 3 + 1] = horizontal * (lateral.x() * cosine - lateral.z() * sine);
            coefficients[i * 3 + 2] = inner * weight;
            derivativeBound += horizontal * lateralLength * weight;
        }
        // Chain displacement is minus the rotated links, plus the rotated contact-tip offset.
        int tipIndex = frames.size() * 3;
        coefficients[tipIndex] = -NativeRailCurve.dot(tipOffset, lateral);
        coefficients[tipIndex + 1] = -(lateral.x()*tipOffset.z() - lateral.z()*tipOffset.x());
        coefficients[tipIndex + 2] = inner;
        derivativeBound += Math.hypot(tipOffset.x(), tipOffset.z()) * lateralLength;
        double low = 0, high = 0.15, target = BLADE_OPENING * scale;
        if (opening(high, coefficients, inner) < target) throw new InvalidLayout(Problem.SPACE);
        while ((high - low) * derivativeBound > SOLVE_TOLERANCE * scale) {
            double mid = (low + high) / 2;
            if (opening(mid, coefficients, inner) < target) low = mid; else high = mid;
        }
        return inner * (low + high) / 2;
    }

    /** Project the change from the closed pose directly; no world positions or temporary nodes. */
    private static double opening(double angle, double[] coefficients, double inner) {
        double displacement = 0;
        for (int i = 0; i < coefficients.length; i += 3) {
            double rotation = angle * coefficients[i + 2];
            if (rotation == 0) continue;
            displacement += coefficients[i] * (Math.cos(rotation) - 1) + coefficients[i + 1] * Math.sin(rotation);
        }
        return inner * displacement;
    }

    private static void railRun(LinkedHashSet<Part> parts, Path path, double a, double b,
                                TrackVector start, TrackVector end, double side, double scale, double startYaw) {
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
            double first = index == 1 ? startYaw : frames.get(index - 2).yaw()
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
        return direction(path.at(a).position(), path.at(b).position());
    }
    private static double direction(TrackVector a, TrackVector b) {
        return Math.atan2(b.x() - a.x(), b.z() - a.z());
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
