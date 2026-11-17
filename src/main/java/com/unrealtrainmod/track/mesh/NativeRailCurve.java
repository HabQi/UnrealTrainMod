/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/** Immutable preparation-time spine; derived polynomial storage is not part of geometry identity. */
public final class NativeRailCurve {
    private final List<Knot> knots;
    private final TrackVector up;
    private final Span[] spans;
    private final int hash;
    public NativeRailCurve(List<Knot> knots) { this(knots, new TrackVector(0, 1, 0)); }
    public NativeRailCurve(List<Knot> knots, TrackVector up) {
        if (knots.size() < 2 || !(knots.getLast().distance() > knots.getFirst().distance()))
            throw new IllegalArgumentException("Rail curve requires a positive station interval");
        // Sample stations and strip boundaries can differ by only a few rounding bits.
        // Keep the exact endpoints, without fitting a polynomial across that microscopic gap.
        var distinct = new ArrayList<Knot>(); distinct.add(knots.getFirst());
        double tolerance = 1e-7*Math.max(1,knots.getLast().distance()-knots.getFirst().distance());
        for (int i = 1; i+1 < knots.size(); i++) {
            var knot = knots.get(i);
            if (knot.distance()-distinct.getLast().distance() > tolerance
                    && knots.getLast().distance()-knot.distance() > tolerance) distinct.add(knot);
        }
        distinct.add(knots.getLast());
        this.knots = List.copyOf(distinct); this.up = up;
        spans = new Span[this.knots.size()-1];
        for (int i = 0; i < spans.length; i++) spans[i] = new Span(this.knots.get(i), this.knots.get(i+1));
        hash = 31*this.knots.hashCode()+up.hashCode();
    }
    public List<Knot> knots() { return knots; }
    public TrackVector up() { return up; }
    public record Knot(double distance, TrackVector point, TrackVector first, TrackVector second) {}
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof NativeRailCurve curve && knots.equals(curve.knots) && up.equals(curve.up);
    }
    @Override public String toString() { return "NativeRailCurve[knots="+knots+", up="+up+"]"; }

    private int upperBound(double distance) {
        int low = 0, high = knots.size();
        while (low < high) {
            int mid = (low+high)/2;
            if (knots.get(mid).distance() <= distance) low = mid+1; else high = mid;
        }
        return low;
    }
    public Knot at(double distance) {
        var first = knots.getFirst(); var last = knots.getLast();
        if (distance < first.distance()) return extend(first,distance);
        if (distance > last.distance()) return extend(last,distance);
        int index = Math.clamp(upperBound(distance)-1, 0, spans.length-1);
        if (distance == last.distance()) return last;
        if (distance == knots.get(index).distance()) return knots.get(index);
        var span = spans[index];
        double t = (distance-span.start)*span.inverse;
        return new Knot(distance, span.value(t, 0), span.value(t, 1), span.value(t, 2));
    }
    private static Knot extend(Knot endpoint, double distance) {
        // End cuts may extend beyond the reference. Never extrapolate a quintic there.
        return new Knot(distance,add(endpoint.point(),endpoint.first(),distance-endpoint.distance()),
                endpoint.first(),new TrackVector(0,0,0));
    }

    private static final class Span {
        final double start, inverse;
        final double[][] coefficients = new double[3][6];
        Span(Knot a, Knot b) {
            double h = b.distance()-a.distance(); start = a.distance(); inverse = 1/h;
            var c0 = a.point(); var c1 = multiply(a.first(), h); var c2 = multiply(a.second(), h*h/2);
            var r = add(add(add(b.point(), c0, -1), c1, -1), c2, -1);
            var v = add(add(multiply(b.first(), h), c1, -1), c2, -2);
            var w = add(multiply(b.second(), h*h), c2, -2);
            var c3 = add(add(multiply(r, 10), v, -4), w, 0.5);
            var c4 = add(add(multiply(r, -15), v, 7), w, -1);
            var c5 = add(add(multiply(r, 6), v, -3), w, 0.5);
            var values = List.of(c0, c1, c2, c3, c4, c5);
            for (int i = 0; i < 6; i++) {
                var p = values.get(i); coefficients[0][i] = p.x(); coefficients[1][i] = p.y(); coefficients[2][i] = p.z();
            }
        }
        TrackVector value(double t, int derivative) {
            return new TrackVector(value(0, t, derivative), value(1, t, derivative), value(2, t, derivative));
        }
        private double value(int axis, double t, int derivative) {
            var c = coefficients[axis];
            double result = c[5]*(derivative == 0 ? 1 : derivative == 1 ? 5 : 20);
            for (int i = 4; i >= derivative; i--) result = result*t+c[i]*(derivative == 0 ? 1 : derivative == 1 ? i : i*(i-1));
            return result*(derivative == 0 ? 1 : derivative == 1 ? inverse : inverse*inverse);
        }
    }

    public static NativeRailCurve fit(DoubleFunction<Knot> sample, List<Double> boundaries, double tolerance) {
        var result = new ArrayList<Knot>();
        var start = sample.apply(boundaries.getFirst()); result.add(start);
        for (int i = 1; i < boundaries.size(); i++) {
            var end = sample.apply(boundaries.get(i));
            fit(sample, start, end, tolerance, result); start = end;
        }
        return new NativeRailCurve(result);
    }
    private static void fit(DoubleFunction<Knot> sample, Knot start, Knot end, double tolerance, List<Knot> result) {
        var span = new Span(start, end); Knot middle = null; boolean split = false;
        for (int i = 1; i < 4; i++) {
            double s = start.distance()+(end.distance()-start.distance())*i/4;
            var actual = sample.apply(s); var fitted = span.value((s-span.start)*span.inverse, 0);
            if (i == 2) middle = actual;
            var delta = add(actual.point(), fitted, -1); split |= dot(delta, delta) > tolerance*tolerance;
        }
        if (split) { fit(sample, start, middle, tolerance, result); fit(sample, middle, end, tolerance, result); }
        else result.add(end);
    }

    /** Slice only the selected knot interval, using one fixed inverse rigid transform. */
    public NativeRailCurve local(double start, double end, TrackVector origin, double yaw, double pitch, double scale, double length) {
        return local(start,end,origin,yaw,pitch,scale,length,0,0);
    }
    /** Keep the parent curve over the complete sheared end section, including miter overhang. */
    public NativeRailCurve local(double start, double end, TrackVector origin, double yaw, double pitch, double scale, double length,
                                double startExtension, double endExtension) {
        double low = start-startExtension, high = end+endExtension;
        var selected = new ArrayList<Knot>(); selected.add(at(low));
        for (int i = upperBound(low); i < knots.size() && knots.get(i).distance() < high; i++) selected.add(knots.get(i));
        selected.add(at(high));
        double parameter = (end-start)/length, sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        var result = new ArrayList<Knot>(selected.size());
        for (var knot : selected) result.add(new Knot((knot.distance()-start)/parameter,
                multiply(rotate(add(knot.point(), origin, -1), sy, cy, sp, cp), 1/scale),
                multiply(rotate(knot.first(), sy, cy, sp, cp), parameter/scale),
                multiply(rotate(knot.second(), sy, cy, sp, cp), parameter*parameter/scale)));
        return new NativeRailCurve(result, rotate(up, sy, cy, sp, cp));
    }
    private static TrackVector rotate(TrackVector p, double sy, double cy, double sp, double cp) {
        double x = cy*p.x()-sy*p.z(), z = sy*p.x()+cy*p.z();
        return new TrackVector(x, cp*p.y()-sp*z, sp*p.y()+cp*z);
    }
    public static TrackVector add(TrackVector a, TrackVector b, double factor) {
        return new TrackVector(a.x()+b.x()*factor, a.y()+b.y()*factor, a.z()+b.z()*factor);
    }
    public static TrackVector multiply(TrackVector p, double factor) { return new TrackVector(p.x()*factor, p.y()*factor, p.z()*factor); }
    public static double dot(TrackVector a, TrackVector b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    public static TrackVector cross(TrackVector a, TrackVector b) {
        return new TrackVector(a.y()*b.z()-a.z()*b.y(), a.z()*b.x()-a.x()*b.z(), a.x()*b.y()-a.y()*b.x());
    }
}
