/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeRailCurve;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.BladeContact;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.RailBend;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Source preparation only. Curves are local polynomials, never per-vertex path samples. */
final class NativeTurnoutCurves {
    private static final double HEAD_HALF = 0.0354982;
    private NativeTurnoutCurves() {}

    private static void normal(float[] data, int i, double x, double y, double z) {
        double length = Math.sqrt(x*x + y*y + z*z);
        if (length > 0) { data[i + 5] = (float)(x / length); data[i + 6] = (float)(y / length); data[i + 7] = (float)(z / length); }
    }

    private static final Map<IndexedMesh, Profile> PROFILES = new IdentityHashMap<>();
    private static final class Profile {
        final TreeMap<Float, Float> values = new TreeMap<>();
        Profile(IndexedMesh rail) {
            var data = rail.vertices();
            for (int i = 0; i < data.length; i += 8) if (Math.abs(data[i+7]) < 0.1f) values.put(data[i+4], data[i]);
        }
        double target(float v) {
            float key = Math.clamp(v, values.firstKey(), values.lastKey());
            var a = values.floorEntry(key); var b = values.ceilingEntry(key);
            double value = a.getValue();
            return a.getKey().equals(b.getKey()) ? value : value+(b.getValue()-value)*(key-a.getKey())/(b.getKey()-a.getKey());
        }
    }
    static void clearProfiles() { PROFILES.clear(); }
    private record BladeSection(double weight, double tailSlope, double upper, double upperSlope,
                                double lower, double lowerSlope, double heightOffset, double heightSlope) {}
    private static BladeSection section(float z, double tip, float length, BladeContact contact) {
        double distance = z-tip, side = contact.side(), tipEdge = side*contact.edges().getFirst().offset();
        int low = 1, high = contact.edges().size()-1;
        while (low < high) {
            int mid = (low+high)/2;
            if (contact.edges().get(mid).distance() < distance) low = mid+1; else high = mid;
        }
        var a = contact.edges().get(low-1); var b = contact.edges().get(low);
        double slope = side*(b.offset()-a.offset())/(b.distance()-a.distance());
        double edge = side*a.offset()+(distance-a.distance())*slope, upper = edge, upperSlope = slope;
        if (edge >= 1.2*HEAD_HALF) { upper = HEAD_HALF; upperSlope = 0; }
        else if (edge > 0.8*HEAD_HALF) {
            double t = (edge/HEAD_HALF-0.8)/0.4;
            upper = HEAD_HALF*(0.8+0.4*t-0.2*t*t); upperSlope *= 1-t;
        }
        double t = Math.clamp((z-0.25)/0.4, 0, 1), u = Math.clamp(distance/length, 0, 1);
        double smooth = u*u*u*(10+u*(-15+6*u)), derivative = 30*u*u*(1-u)*(1-u)/length;
        return new BladeSection(t*t*(3-2*t), 6*t*(1-t)/0.4, upper, upperSlope,
                -HEAD_HALF+(tipEdge+HEAD_HALF)*(1-smooth), -(tipEdge+HEAD_HALF)*derivative,
                contact.tipHeight()*(1-smooth), -contact.tipHeight()*derivative);
    }

    static IndexedMesh blade(IndexedMesh source, IndexedMesh rail, float length, BladeContact contact) {
        var vertices = source.vertices().clone(); double factor = length / (0.65 + 1.572079);
        for (int i = 0; i < vertices.length; i += 8) {
            vertices[i + 2] = (float)(0.65 + (vertices[i + 2] - 0.65) * factor);
            normal(vertices, i, vertices[i + 5], vertices[i + 6], vertices[i + 7] / factor);
        }
        var stretched = new IndexedMesh(vertices, source.colors(), source.indices());
        var profile = PROFILES.computeIfAbsent(rail, Profile::new);
        double side = contact.side(), center = side * 0.74875, tip = 0.65f - length;
        var cuts = new TreeSet<Float>(); cuts.add((float)tip); cuts.add(0.65f);
        for (var edge : contact.edges()) cuts.add((float)(tip + edge.distance()));
        for (var knot : contact.curve().knots()) cuts.add((float)(tip + knot.distance()));
        for (int i = 0; i <= 4; i++) cuts.add(0.25f + i * 0.1f);
        var planes = new ArrayList<>(cuts); var sections = new ArrayList<IndexedMesh>();
        var sweeps = new HashMap<Float, Sweep>(); var shapes = new HashMap<Float, BladeSection>();
        var targets = new HashMap<Float, Double>();
        var bands = NativeRailSlices.split(stretched, planes);
        for (var band : bands) {
            var data = band.vertices();
            for (int i = 0; i < data.length; i += 8) {
                double target = targets.computeIfAbsent(data[i+4], profile::target);
                var shape = shapes.computeIfAbsent(data[i+2], z -> section(z, tip, length, contact));
                double delta = target-data[i], nx = data[i+5];
                double nz = (1-shape.weight())*data[i+7]-nx*shape.tailSlope()*delta;
                data[i] += (float)(shape.weight()*delta); normal(data, i, nx, data[i+6], nz);
                double upper = shape.upper(), lower = shape.lower(), upperSlope = shape.upperSlope(), lowerSlope = shape.lowerSlope();
                double ratio = (side*(target-center) + HEAD_HALF)/(2*HEAD_HALF);
                double fitted = center + side*(lower + (upper-lower)*ratio);
                double width = (upper-lower)/(2*HEAD_HALF);
                double fittedSlope = side*(lowerSlope + (upperSlope-lowerSlope)*ratio);
                double h = Math.clamp((data[i + 1] + 0.06)/0.035, 0, 1), blend = h*h*(3-2*h);
                double blendSlope = 6*h*(1-h)/0.035, change = fitted-data[i];
                double jacobian = 1-blend + blend*width;
                nx = data[i + 5];
                normal(data, i, nx, data[i + 6]*jacobian-nx*blendSlope*change,
                        data[i + 7]*jacobian-nx*blend*fittedSlope);
                data[i] += (float)(blend*change);
                double heightOffset = shape.heightOffset(), heightSlope = shape.heightSlope();
                double heightJacobian = 1+heightOffset*blendSlope;
                normal(data, i, data[i+5]*heightJacobian, data[i+6],
                        data[i+7]*heightJacobian-data[i+6]*heightSlope*blend);
                data[i+1] += (float)(heightOffset*blend);
            }
            sweep(data, (float)tip, center, contact.curve(), sweeps);
            sections.add(band);
        }
        return NativeTurnoutMeshes.join(sections);
    }

    private record Sweep(TrackVector point, TrackVector first, TrackVector lateral, TrackVector normal,
                         TrackVector lateralDerivative, TrackVector normalDerivative) {}

    private static Sweep sweep(NativeRailCurve curve, double distance) {
        var k = curve.at(distance); var v = k.first(); var a = k.second();
        double speed = Math.sqrt(NativeRailCurve.dot(v, v));
        var tangent = NativeRailCurve.multiply(v, 1/speed);
        var derivative = NativeRailCurve.multiply(NativeRailCurve.add(a, tangent, -NativeRailCurve.dot(tangent, a)), 1/speed);
        var right = NativeRailCurve.cross(curve.up(), v);
        double rightLength = Math.sqrt(NativeRailCurve.dot(right, right));
        var lateral = NativeRailCurve.multiply(right, 1/rightLength);
        var rightDerivative = NativeRailCurve.cross(curve.up(), a);
        var lateralDerivative = NativeRailCurve.multiply(NativeRailCurve.add(rightDerivative, lateral,
                -NativeRailCurve.dot(lateral, rightDerivative)), 1/rightLength);
        var up = NativeRailCurve.cross(tangent, lateral);
        var upDerivative = NativeRailCurve.add(NativeRailCurve.cross(derivative, lateral), NativeRailCurve.cross(tangent, lateralDerivative), 1);
        return new Sweep(k.point(), v, lateral, up, lateralDerivative, upDerivative);
    }

    private static void sweep(float[] data, float start, double center, NativeRailCurve curve, Map<Float, Sweep> cache) {
        for (int i = 0; i < data.length; i += 8) {
            float z = data[i + 2]; var s = cache.computeIfAbsent(z, key -> sweep(curve, key-start));
            double across = data[i]-center, height = data[i+1];
            var point = NativeRailCurve.add(NativeRailCurve.add(s.point(), s.lateral(), across), s.normal(), height);
            var forward = NativeRailCurve.add(NativeRailCurve.add(s.first(), s.lateralDerivative(), across), s.normalDerivative(), height);
            var nx = NativeRailCurve.cross(s.normal(), forward); var ny = NativeRailCurve.cross(forward, s.lateral());
            var nz = NativeRailCurve.cross(s.lateral(), s.normal());
            var n = NativeRailCurve.add(NativeRailCurve.add(NativeRailCurve.multiply(nx, data[i+5]), ny, data[i+6]), nz, data[i+7]);
            data[i] = (float)(center+point.x()); data[i+1] = (float)point.y(); data[i+2] = (float)(start+point.z());
            normal(data, i, n.x(), n.y(), n.z());
        }
    }

    static IndexedMesh rail(IndexedMesh source, float length, RailBend bend, double center) {
        if (bend.curve() != null) {
            var sections = new ArrayList<IndexedMesh>(); var sweeps = new HashMap<Float, Sweep>();
            float start = -length/2;
            var cuts = new ArrayList<Float>();
            for (var knot : bend.curve().knots()) cuts.add(start+(float)knot.distance());
            for (var band : NativeRailSlices.split(source, cuts)) {
                sweep(band.vertices(), start, center, bend.curve(), sweeps); sections.add(band);
            }
            return NativeTurnoutMeshes.join(sections);
        }
        if (bend.start() == 0 && bend.end() == 0) return NativeTurnoutMeshes.trim(source, -length / 2 - 0.0005f, length / 2 + 0.0005f);
        double startSlope = Math.tan(bend.start()), endSlope = Math.tan(bend.end());
        int count = Math.max(1, (int)Math.ceil(length / 0.1));
        var sections = new ArrayList<IndexedMesh>(); var cuts = new ArrayList<Float>();
        for (int i = 0; i <= count; i++) cuts.add(i == 0 ? -length/2-0.0005f : i == count ? length/2+0.0005f : -length/2+length*i/count);
        for (var band : NativeRailSlices.split(source, cuts)) {
            var data = band.vertices();
            for (int i = 0; i < data.length; i += 8) {
                double t = (data[i + 2] + length / 2) / length;
                double offset = length * ((t*t*t - 2*t*t + t)*startSlope + (t*t*t - t*t)*endSlope);
                double slope = (3*t*t - 4*t + 1)*startSlope + (3*t*t - 2*t)*endSlope;
                double cosine = 1 / Math.sqrt(1 + slope*slope), sine = slope*cosine;
                double across = data[i] - center;
                data[i] = (float)(center + offset + across*cosine);
                data[i + 2] -= (float)(across*sine);
                normal(data, i, data[i + 5]*cosine + data[i + 7]*sine, data[i + 6], -data[i + 5]*sine + data[i + 7]*cosine);
            }
            sections.add(band);
        }
        return NativeTurnoutMeshes.join(sections);
    }
}
