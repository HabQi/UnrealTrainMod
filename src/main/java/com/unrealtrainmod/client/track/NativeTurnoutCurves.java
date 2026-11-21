/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeRailCurve;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.BladeContact;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.RailBend;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/** Source preparation only: deform existing vertices without longitudinal subdivision. */
final class NativeTurnoutCurves {
    private static final double HEAD_HALF = 0.0354982;
    private NativeTurnoutCurves() {}

    private static void normal(float[] data, int i, double x, double y, double z) {
        double length = Math.sqrt(x*x + y*y + z*z);
        if (length > 0) { data[i + 5] = (float)(x / length); data[i + 6] = (float)(y / length); data[i + 7] = (float)(z / length); }
    }

    private static final Map<IndexedMesh, Profile> PROFILES = new IdentityHashMap<>();
    // Resource metadata follows its source mesh across world/cache resets, without retaining old reloads.
    private static final Map<IndexedMesh, float[]> PARAMETERS = Collections.synchronizedMap(new WeakHashMap<>());
    static void parameters(IndexedMesh mesh, float[] corners) throws IOException {
        if (corners.length != mesh.indices().length) throw new IOException("Native rail parameter corner count does not match OBJ triangles");
        var values = new float[mesh.colors().length]; Arrays.fill(values, Float.NaN);
        for (int i = 0; i < corners.length; i++) {
            int vertex = mesh.indices()[i]; float value = corners[i];
            if (!Float.isFinite(value) || value < 0 || value > 1) throw new IOException("Invalid native rail parameter V");
            if (!Float.isNaN(values[vertex]) && Math.abs(values[vertex]-value) > 0.000001f)
                throw new IOException("Shared native OBJ vertex has conflicting deformation parameters");
            values[vertex] = value;
        }
        for (float value : values) if (Float.isNaN(value)) throw new IOException("Native rail vertex has no deformation parameter");
        PARAMETERS.put(mesh, values);
    }
    private static final class Profile {
        final TreeMap<Float, Float> values = new TreeMap<>();
        Profile(IndexedMesh rail) {
            var data = rail.vertices();
            var parameters = PARAMETERS.get(rail);
            for (int i = 0; i < data.length; i += 8) if (Math.abs(data[i+7]) < 0.1f) values.put(parameters[i/8], data[i]);
        }
        double target(float v) {
            float key = Math.clamp(v, values.firstKey(), values.lastKey());
            var a = values.floorEntry(key); var b = values.ceilingEntry(key);
            double value = a.getValue();
            return a.getKey().equals(b.getKey()) ? value : value+(b.getValue()-value)*(key-a.getKey())/(b.getKey()-a.getKey());
        }
    }
    static void clearProfiles() { synchronized (PROFILES) { PROFILES.clear(); } }
    private static Profile profile(IndexedMesh mesh) {
        synchronized (PROFILES) { var cached = PROFILES.get(mesh); if (cached != null) return cached; }
        var built = new Profile(mesh);
        synchronized (PROFILES) { return PROFILES.computeIfAbsent(mesh, ignored -> built); }
    }
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
        var profile = profile(rail);
        var parameters = PARAMETERS.get(source);
        for (int i = 0; i < vertices.length; i += 8) {
            vertices[i + 2] = (float)(0.65 + (vertices[i + 2] - 0.65) * factor);
            normal(vertices, i, vertices[i + 5], vertices[i + 6], vertices[i + 7] / factor);
        }
        var stretched = new IndexedMesh(vertices, source.colors(), source.indices());
        double side = contact.side(), center = side * 0.74875, tip = 0.65f - length;
        var clipped = NativeRailClip.clip(stretched, (float)tip, 0.65f, parameters);
        var mesh = clipped.mesh(); var data = mesh.vertices();
        var shapes = new HashMap<Float, BladeSection>();
        var targets = new HashMap<Float, Double>();
        for (int i = 0; i < data.length; i += 8) {
            double target = targets.computeIfAbsent(clipped.parameters()[i/8], profile::target);
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
        sweep(data, (float)tip, center, contact.curve(), new HashMap<>());
        return mesh;
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
            float z = data[i + 2]; var s = cache.computeIfAbsent(z, key -> sweep(curve, (double)key-start));
            sweep(data,i,start,center,s);
        }
    }
    private static void sweep(float[] data, int i, double start, double center, Sweep s) {
        double across = data[i]-center, height = data[i+1];
        var point = NativeRailCurve.add(NativeRailCurve.add(s.point(), s.lateral(), across), s.normal(), height);
        var forward = NativeRailCurve.add(NativeRailCurve.add(s.first(), s.lateralDerivative(), across), s.normalDerivative(), height);
        var nx = NativeRailCurve.cross(s.normal(), forward); var ny = NativeRailCurve.cross(forward, s.lateral());
        var nz = NativeRailCurve.cross(s.lateral(), s.normal());
        var n = NativeRailCurve.add(NativeRailCurve.add(NativeRailCurve.multiply(nx, data[i+5]), ny, data[i+6]), nz, data[i+7]);
        data[i] = (float)(center+point.x()); data[i+1] = (float)point.y(); data[i+2] = (float)(start+point.z());
        normal(data, i, n.x(), n.y(), n.z());
    }

    /** Sweep an authored cross section during static baking, retaining its UVs and source profile. */
    static IndexedMesh follow(IndexedMesh source, float start, double center, NativeRailCurve curve) {
        var vertices = source.vertices().clone();
        sweep(vertices, start, center, curve, new HashMap<>());
        return new IndexedMesh(vertices, source.colors(), source.indices());
    }

    /** Two authored end sections, including miter shear; no serial clipping or added strip stations. */
    static IndexedMesh fixedRail(IndexedMesh source, double start, double end, double center, NativeRailCurve curve,
                                 double startMiter, double endMiter) {
        var data = source.vertices().clone(); float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int i = 2; i < data.length; i += 8) { min = Math.min(min,data[i]); max = Math.max(max,data[i]); }
        double span = (double)max-min, factor = (end-start)/span;
        var frames = new HashMap<Double,Sweep>();
        for (int i = 0; i < data.length; i += 8) {
            double t = ((double)data[i+2]-min)/span, across = data[i]-center;
            double shear = -(1-t)*startMiter+t*endMiter, jacobian = factor+across*(startMiter+endMiter)/span;
            double nz = data[i+7]/jacobian;
            normal(data,i,data[i+5]-nz*shear,data[i+6],nz);
            // Evaluate from the exact double station; a float round trip can cross a curve boundary.
            double station = (end-start)*t+across*shear;
            if (curve == null) data[i+2] = (float)(start+station);
            else sweep(data,i,start,center,frames.computeIfAbsent(station,s -> sweep(curve,s)));
        }
        return new IndexedMesh(data,source.colors(),source.indices());
    }

    record SlipSection(float rightX, float rightZ, float width, float footLimit, float bevel, float footRelief) {}
    record SlipShape(SlipSection start, SlipSection end, float length, int side, float visibleFrom) {}
    record SlipGeometry(IndexedMesh mesh, float[] sections) {}

    static IndexedMesh slipStrip(IndexedMesh source, SlipShape shape) {
        return shape.visibleFrom() == 0 && shape.start().footRelief() == 0 && shape.end().footRelief() == 0
                ? deformSlipStrip(source,shape,null) : slipGeometry(source,shape).mesh();
    }

    /** Retain the original section parameter on the actual triangles, including a clipped toe. */
    static SlipGeometry slipGeometry(IndexedMesh source, SlipShape shape) {
        var sections = new float[source.colors().length];
        var mesh = deformSlipStrip(source,shape,sections);
        float floor = -.176f+shape.start().footRelief();
        float slope = shape.end().footRelief()-shape.start().footRelief();
        if (shape.start().footRelief() > 0 || shape.end().footRelief() > 0) {
            // Clip the underside of the toe on a sloped plane, retaining its railhead and web.
            var vertices = mesh.vertices();
            for (int i = 0; i < sections.length; i++) vertices[i*8+1] -= floor+slope*sections[i];
            var clipped = NativeRailClip.clip(mesh,1,0,Float.POSITIVE_INFINITY,sections);
            mesh = clipped.mesh();
            sections = clipped.parameters();
            vertices = mesh.vertices();
            for (int i = 0; i < sections.length; i++) vertices[i*8+1] += floor+slope*sections[i];
        }
        if (shape.visibleFrom() == 0) return new SlipGeometry(mesh,sections);
        var data = mesh.vertices();
        // Clip in the section parameter, carrying the deformed Z through the same interpolation.
        for (int i = 0; i < sections.length; i++) {
            float z = data[i*8+2];
            data[i*8+2] = sections[i];
            sections[i] = z;
        }
        var clipped = NativeRailClip.clip(mesh,shape.visibleFrom(),1,sections);
        var vertices = clipped.mesh().vertices();
        var retained = new float[clipped.mesh().colors().length];
        for (int i = 0; i < retained.length; i++) {
            retained[i] = vertices[i*8+2];
            vertices[i*8+2] = clipped.parameters()[i];
        }
        return new SlipGeometry(clipped.mesh(),retained);
    }

    private static IndexedMesh deformSlipStrip(IndexedMesh source, SlipShape shape, float[] sections) {
        var data = source.vertices().clone();
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int i = 2; i < data.length; i += 8) { min = Math.min(min, data[i]); max = Math.max(max, data[i]); }
        double side = shape.side(), span = max-min;
        double head = com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout.HEAD_WIDTH;
        double offset = side * com.unrealtrainmod.track.mesh.NativeTurnoutLayout.RAIL_WORK_X;
        for (int i = 0; i < data.length; i += 8) {
            double t = (data[i+2]-min)/span;
            if (sections != null) sections[i/8] = (float)t;
            var a = shape.start(); var b = shape.end();
            double rightX = Math.fma(b.rightX()-a.rightX(), t, a.rightX());
            double rightZ = Math.fma(b.rightZ()-a.rightZ(), t, a.rightZ());
            double width = Math.fma(b.width()-a.width(), t, a.width());
            double bevel = Math.fma(b.bevel()-a.bevel(),t,a.bevel());
            double across = data[i]-offset;
            boolean foot = data[i+1] < -.12;
            if (width < head-1e-6) {
                // The wider foot narrows with the head on the contact side; the running face stays in place.
                double limit = width * (foot ? .1105/head : 1);
                across = side * Math.min(side*across, limit);
            }
            if (foot) {
                double limit = Math.fma(b.footLimit()-a.footLimit(),t,a.footLimit());
                across = side*Math.min(side*across,limit);
            }
            double nx = data[i+5], nz = data[i+7] * span / shape.length();
            data[i] = (float)(across*rightX);
            // Positive cuts recess the outside of the toe; negative cuts relieve the inside of the heel.
            double setback = bevel*Math.max(0,side*Math.signum(bevel)*across);
            data[i+2] = (float)(shape.length()*t+across*rightZ+setback);
            normal(data, i, nx*rightX-nz*rightZ, data[i+6], nx*rightZ+nz*rightX);
        }
        return new IndexedMesh(data, source.colors(), source.indices());
    }

    static IndexedMesh rail(IndexedMesh source, float length, RailBend bend, double center) {
        float start = -length/2;
        if (bend.curve() != null) {
            var mesh = NativeTurnoutMeshes.trim(source, start, length/2);
            sweep(mesh.vertices(), start, center, bend.curve(), new HashMap<>());
            return mesh;
        }
        var result = NativeTurnoutMeshes.trim(source, start-0.0005f, length/2+0.0005f);
        if (bend.start() == 0 && bend.end() == 0) return result;
        double startSlope = Math.tan(bend.start()), endSlope = Math.tan(bend.end());
        var data = result.vertices();
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
        return result;
    }
}
