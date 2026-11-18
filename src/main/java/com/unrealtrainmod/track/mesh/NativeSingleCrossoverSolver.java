/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.AssemblyPlacementGeometry;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded deterministic geometry search. No world, client, resource or GPU access. */
public final class NativeSingleCrossoverSolver {
    public static final int CANDIDATE_LIMIT = 512, COMPLETE_LIMIT = 32;
    private NativeSingleCrossoverSolver() {}

    public record Constraint(boolean angleLocked, boolean lengthLocked) {}
    public record Input(List<TrackVector> points, double gauge, List<TrackCurveParameters> requested,
                        List<Constraint> constraints, double yaw) {
        public Input {
            points = List.copyOf(points);
            requested = Collections.unmodifiableList(new ArrayList<>(requested));
            constraints = List.copyOf(constraints);
            if (points.size() != 4 || requested.size() != 4 || constraints.size() != 4)
                throw new IllegalArgumentException("Single crossover requires four ports and constraints");
        }
    }
    public record Result(List<TrackCurveParameters> curves, TrackAssemblyDefinition.Geometry geometry,
                         NativeCrossoverLayout layout, boolean adjusted, int candidates, int complete,
                         NativeLayoutDiagnostic diagnostic) {
        public Result { curves = List.copyOf(curves); }
        public boolean found() { return layout != null; }
    }
    private record Evaluation(List<TrackCurveParameters> curves, TrackAssemblyDefinition.Geometry geometry,
                              double violation, double shape, double change) {}
    private static final Comparator<Evaluation> ORDER = Comparator.comparingDouble(Evaluation::violation)
            .thenComparingDouble(Evaluation::shape).thenComparingDouble(Evaluation::change);

    public static Result solve(Input input, BooleanSupplier cancelled) {
        return new Search(input, cancelled).solve();
    }

    private static final class Search {
        final Input input;
        final BooleanSupplier cancelled;
        final List<TrackCurveParameters> baseline;
        final TrackAssemblyDefinition.Geometry original;
        final Set<List<TrackCurveParameters>> seen = new HashSet<>();
        final Set<List<TrackCurveParameters>> checked = new HashSet<>();
        final List<Evaluation> finalists = new ArrayList<>();
        NativeLayoutDiagnostic diagnostic;
        int candidates, complete;

        Search(Input input, BooleanSupplier cancelled) {
            this.input = input; this.cancelled = cancelled;
            baseline = AssemblyPlacementGeometry.defaults(TrackConstructionType.SINGLE_CROSSOVER,
                    input.points(), input.requested(), input.yaw());
            TrackAssemblyDefinition.Geometry originalGeometry;
            try { originalGeometry = geometry(baseline); }
            catch (IllegalArgumentException failure) { originalGeometry = null; }
            original = originalGeometry;
        }
        Result solve() {
            checkCancelled(); candidates++;
            if (original != null) {
                complete++;
                try {
                    var layout = NativeCrossoverLayout.build(original, input.gauge());
                    return new Result(baseline, original, layout, false, candidates, complete, null);
                } catch (NativeCrossoverLayout.InvalidLayout failure) { diagnostic = failure.diagnostic(); }
                catch (IllegalArgumentException failure) { diagnostic = generic(); }
                seen.add(baseline);
                checked.add(baseline);
            }

            for (int phase = 0; phase < 2; phase++) {
                int limit = (phase+1)*CANDIDATE_LIMIT/2;
                for (double factor : new double[]{1, .5, .75, 1.25, 1.5}) {
                    if (candidates >= limit) break;
                    var seed = new ArrayList<>(baseline);
                    for (int port = 0; port < 4; port++) if (!input.constraints().get(port).lengthLocked()) {
                        var curve = seed.get(port);
                        seed.set(port, new TrackCurveParameters(curve.angle(), length(curve.length()*factor)));
                    }
                    var current = evaluate(seed);
                    for (int round = 0; round < 4 && candidates < limit; round++) {
                        for (int port = 0; port < 4 && candidates < limit; port++) {
                            var constraint = input.constraints().get(port);
                            for (int dimension = 0; dimension <= phase && candidates < limit; dimension++) {
                                if (dimension == 0 ? constraint.lengthLocked() : constraint.angleLocked()) continue;
                                var anchor = current;
                                for (int sign : new int[]{-1,1}) {
                                    if (candidates >= limit) break;
                                    var values = new ArrayList<>(anchor.curves()); var curve = values.get(port);
                                    double step = dimension == 0 ? Math.max(.01, baseline.get(port).length()*.25/Math.pow(2,round))
                                            : 10/Math.pow(2,round);
                                    values.set(port, dimension == 0
                                            ? new TrackCurveParameters(curve.angle(), length(curve.length()+sign*step))
                                            : new TrackCurveParameters(curve.angle()+sign*step, curve.length()));
                                    var next = evaluate(values);
                                    if (ORDER.compare(next,current) < 0) current = next;
                                }
                            }
                        }
                    }
                }
                // Open automatic directions only if length-only layouts did not pass full validation.
                var result = finish(phase == 0 ? COMPLETE_LIMIT/2+1 : COMPLETE_LIMIT);
                if (result != null) return result;
            }
            return new Result(baseline, original, null, false, candidates, complete, diagnostic);
        }
        Result finish(int limit) {
            finalists.sort(ORDER);
            for (var candidate : finalists) {
                if (complete >= limit) break;
                if (!checked.add(candidate.curves())) continue;
                checkCancelled(); complete++;
                try {
                    var layout = NativeCrossoverLayout.build(candidate.geometry(), input.gauge());
                    return new Result(candidate.curves(), candidate.geometry(), layout,
                            !candidate.curves().equals(baseline), candidates, complete, null);
                } catch (NativeCrossoverLayout.InvalidLayout failure) { diagnostic = failure.diagnostic(); }
                catch (IllegalArgumentException failure) { diagnostic = generic(); }
            }
            return null;
        }
        Evaluation evaluate(List<TrackCurveParameters> values) {
            checkCancelled();
            var curves = List.copyOf(values);
            // Duplicate candidates still consume budget, keeping every search bounded.
            candidates++;
            TrackAssemblyDefinition.Geometry geometry = null;
            double violation = 0, shape = Double.MAX_VALUE, change = 0;
            NativeLayoutDiagnostic failure = null;
            for (int i = 0; i < 4; i++) {
                var a = baseline.get(i); var b = curves.get(i);
                double angle = Math.toRadians(b.angle()-a.angle());
                change += Math.pow((b.length()-a.length())/Math.max(1,a.length()),2)+2*(1-Math.cos(angle));
            }
            try {
                geometry = geometry(curves);
                shape = displacement(geometry);
                NativeCrossoverLayout.candidate(geometry,input.gauge());
            } catch (NativeCrossoverLayout.InvalidLayout invalid) {
                failure = invalid.diagnostic(); violation = violation(failure);
            } catch (IllegalArgumentException invalid) { failure = generic(); violation = 1_000_000; }
            var result = new Evaluation(curves,geometry,violation,shape,change);
            if (violation == 0 && seen.add(curves)) {
                finalists.add(result); finalists.sort(ORDER);
                if (finalists.size() > COMPLETE_LIMIT-1) finalists.removeLast();
            }
            if (failure != null) diagnostic = failure;
            return result;
        }
        TrackAssemblyDefinition.Geometry geometry(List<TrackCurveParameters> curves) {
            var geometry = AssemblyPlacementGeometry.create(TrackConstructionType.SINGLE_CROSSOVER,
                    input.points(),input.gauge(),curves,input.yaw());
            AssemblyPlacementGeometry.validate(TrackAssemblyDefinition.SINGLE_CROSSOVER,geometry);
            return geometry;
        }
        double displacement(TrackAssemblyDefinition.Geometry geometry) {
            if (original == null) return 0;
            double sum = 0;
            for (String name : List.of("first_straight","second_straight")) {
                var a = original.paths().get(name).getFirst(); var b = geometry.paths().get(name).getFirst();
                for (int i = 1; i < 16; i++) {
                    var p = a.sample(a.getLength()*i/16).position(); var q = b.sample(b.getLength()*i/16).position();
                    sum += Math.pow(p.x()-q.x(),2)+Math.pow(p.z()-q.z(),2);
                }
            }
            return sum/(30*input.gauge()*input.gauge());
        }
        void checkCancelled() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
    }
    private static double length(double value) {
        return Math.clamp(value,TrackCurveParameters.MIN_LENGTH,TrackCurveParameters.MAX_LENGTH);
    }
    private static NativeLayoutDiagnostic generic() {
        return NativeLayoutDiagnostic.at("curve_constraints","layout",0,0,0,0);
    }
    private static double violation(NativeLayoutDiagnostic diagnostic) {
        double required = diagnostic.required(), actual = diagnostic.to()-diagnostic.from();
        double shortage = required > 0 ? Math.max(0,(required-actual)/required) : 1;
        double stage = diagnostic.stage().contains("intersection") || diagnostic.stage().contains("cusp") ? 10_000
                : diagnostic.stage().contains("connection") ? 10 : 100;
        return stage+Math.min(1_000,Double.isFinite(shortage) ? shortage : 1_000);
    }
}
