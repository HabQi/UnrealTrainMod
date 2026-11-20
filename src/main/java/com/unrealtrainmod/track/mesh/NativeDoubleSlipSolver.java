/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;

/** Solves tangent slip connections between the two main lines. */
public final class NativeDoubleSlipSolver {
    static final double MAX_INSET = .20;
    private static final int INSET_STEPS = 4;
    private NativeDoubleSlipSolver() {}
    public enum Problem { INTERSECTION, ANGLE, SPACE }
    public static final class InvalidLayout extends IllegalArgumentException {
        private final Problem problem;
        public InvalidLayout(Problem problem) { super("DOUBLE_SLIP_" + problem.name()); this.problem = problem; }
        public InvalidLayout(Problem problem, NativeLayoutDiagnostic diagnostic) {
            super("DOUBLE_SLIP_" + problem.name() + ": " + diagnostic); this.problem = problem;
        }
        public Problem problem() { return problem; }
    }
    public record Solution(List<TrackGeometry> first, List<TrackGeometry> second) {}

    public static double workingOffset(double gauge) { return NativeTurnoutLayout.RAIL_WORK_X * gauge / 1.435; }

    public static Solution solve(BezierTrackGeometry first, BezierTrackGeometry second, double gauge) {
        var ends = ends(first, second, gauge);
        double scale = gauge / 1.435;
        for (int step = INSET_STEPS; step > 0; step--) {
            double inset = MAX_INSET * step / INSET_STEPS * scale;
            try {
                return candidate(first,second,ends,gauge,scale,inset);
            } catch (IllegalArgumentException rejected) {
                // Try a smaller paired inset before reporting the original layout's diagnostic.
            }
        }
        return candidate(first,second,ends,gauge,scale,0);
    }

    private static Solution candidate(BezierTrackGeometry first, BezierTrackGeometry second,
                                      NativeDiamondLayout.SlipEnds ends, double gauge, double scale, double inset) {
        var solution = new Solution(path(first,second,ends,true,gauge,inset),path(second,first,ends,false,gauge,inset));
        NativeDoubleSlipLayout.validateBlades(first,second,solution,ends,scale);
        return solution;
    }

    private static NativeDiamondLayout.SlipEnds ends(TrackGeometry first, TrackGeometry second, double gauge) {
        try { return NativeDiamondLayout.slipEnds(first, second, gauge); }
        catch (NativeDiamondLayout.InvalidLayout failure) {
            throw new InvalidLayout(Problem.valueOf(failure.problem().name()));
        }
    }

    private static List<TrackGeometry> path(BezierTrackGeometry entry, BezierTrackGeometry exit,
            NativeDiamondLayout.SlipEnds ends, boolean first, double gauge, double inset) {
        String name = first ? "first_crossover" : "second_crossover";
        int inner = first ? ends.firstInnerSide() : -ends.firstInnerSide();
        double from = (first ? ends.entrance().firstStation() : ends.entrance().secondStation()) + inset;
        double to = (first ? ends.exit().secondStation() : ends.exit().firstStation()) - inset;
        double approach = NativeDiamondLayout.ORDINARY_PERIOD * gauge / 1.435;
        if (from < approach || exit.getLength() - to < approach || from >= entry.getLength() || to <= 0)
            throw new InvalidLayout(Problem.SPACE,
                NativeLayoutDiagnostic.at("double_slip_approach_space", name, inner, 0,
                        Math.min(from, exit.getLength() - to), approach));
        var a = entry.sample(from); var b = exit.sample(to);
        var delta = add(b.position(), a.position(), -1);
        double alignment = dot(a.tangent(), b.tangent());
        if (alignment <= 0 || inner * dot(b.tangent(), a.lateral()) >= 0) throw new InvalidLayout(Problem.SPACE);
        double determinant = cross(a.tangent(), b.tangent());
        double entryArm = cross(delta, b.tangent()) / determinant;
        double exitArm = cross(a.tangent(), delta) / determinant;
        if (entryArm <= 0 || exitArm <= 0) throw new InvalidLayout(Problem.SPACE,
                NativeLayoutDiagnostic.at("double_slip_tangent_order", name, inner, from, to, 0));
        double cosine = Math.sqrt((1 + Math.min(1, alignment)) / 2);
        double factor = 4 * cosine / (3 * (1 + cosine));
        var c1 = add(a.position(), a.tangent(), factor * entryArm);
        var c2 = add(b.position(), b.tangent(), -factor * exitArm);
        var curve = new BezierTrackGeometry(point(a.position()), point(c1), point(c2), point(b.position()),
                new LinearGrade(a.position().y(), b.position().y()), gauge);
        checkBend(curve, ends, inner, gauge, name);
        var lead = entry.subCurve(0,from);
        var trail = exit.subCurve(to,exit.getLength());
        double length = lead.getLength()+curve.getLength()+trail.getLength();
        if (length > TrackPlacementGeometry.MAX_LENGTH) throw new InvalidLayout(Problem.SPACE,
                NativeLayoutDiagnostic.at("double_slip_path_length",name,inner,from,length,TrackPlacementGeometry.MAX_LENGTH));
        return List.of(lead,curve,trail);
    }

    private static void checkBend(TrackGeometry curve, NativeDiamondLayout.SlipEnds ends, int inner, double gauge, String path) {
        double scale = gauge / 1.435, offset = inner * workingOffset(gauge);
        var forward = add(ends.exit().workingPoint(), ends.entrance().workingPoint(), -1);
        for (int i = 0; i <= 64; i++) {
            var pose = curve.sample(curve.getLength() * i / 64);
            var edge = add(pose.position(), pose.lateral(), offset);
            if (dot(pose.tangent(), forward) <= 0 || Math.abs(pose.curvature()) * NativeDiamondLayout.RAIL_X * scale >= 1
                    || inner * pose.curvature() > 0.000001 / scale || !inside(edge, ends.boundary(), 0.001 * scale)) {
                throw new InvalidLayout(Problem.SPACE,
                        NativeLayoutDiagnostic.at("double_slip_inner_rail", path, inner, 0, curve.getLength() * i / 64, 0));
            }
        }
    }

    private static boolean inside(TrackVector point, List<TrackVector> boundary, double tolerance) {
        boolean inside = false;
        for (int i = 0, previous = boundary.size() - 1; i < boundary.size(); previous = i++) {
            var a = boundary.get(previous); var b = boundary.get(i);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double t = Math.clamp(((point.x() - a.x()) * dx + (point.z() - a.z()) * dz) / (dx * dx + dz * dz), 0, 1);
            if (Math.hypot(point.x() - a.x() - t * dx, point.z() - a.z() - t * dz) <= tolerance) return true;
            if ((a.z() > point.z()) != (b.z() > point.z())
                    && point.x() < a.x() + (point.z() - a.z()) * dx / dz) inside = !inside;
        }
        return inside;
    }

    /** Saved bends may start at the original acute vertices or within the inset on their respective main lines. */
    public static void validate(TrackAssemblyDefinition.Geometry geometry) {
        var first = geometry.paths().get("first_straight").getFirst();
        var second = geometry.paths().get("second_straight").getFirst();
        double gauge = first.sample(0).gauge();
        var ends = ends(first, second, gauge);
        contact(geometry.paths().get("first_crossover").get(1),first,second,ends,true,gauge);
        contact(geometry.paths().get("second_crossover").get(1),second,first,ends,false,gauge);
    }

    private static void contact(TrackGeometry curve, TrackGeometry entry, TrackGeometry exit,
                                NativeDiamondLayout.SlipEnds ends, boolean first, double gauge) {
        double scale = gauge / 1.435;
        double from = first ? ends.entrance().firstStation() : ends.entrance().secondStation();
        double to = first ? ends.exit().secondStation() : ends.exit().firstStation();
        NativeDoubleSlipLayout.transitionStation(entry,curve.sample(0),from,scale);
        NativeDoubleSlipLayout.transitionStation(exit.reversed(),curve.reversed().sample(0),exit.getLength()-to,scale);
        checkBend(curve,ends,first ? ends.firstInnerSide() : -ends.firstInnerSide(),gauge,
                first ? "first_crossover" : "second_crossover");
    }

    private static TrackPoint point(TrackVector p) { return new TrackPoint(p.x(), p.z()); }
    private static double dot(TrackVector a, TrackVector b) { return a.x() * b.x() + a.z() * b.z(); }
    private static double cross(TrackVector a, TrackVector b) { return a.x() * b.z() - a.z() * b.x(); }
    private static TrackVector add(TrackVector a, TrackVector b, double factor) {
        return new TrackVector(a.x() + b.x() * factor, a.y() + b.y() * factor, a.z() + b.z() * factor);
    }
}
