/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.*;

/** Preparation-only reference curves and coarse rigid strips; no render-time path sampling. */
public final class NativeFixedRails {
    public static final double MAX_LENGTH = 3.0, ERROR = 0.002;
    private static final double HALF_WIDTH = 0.075, HEIGHT = 0.176;
    private NativeFixedRails() {}

    public static TrackPose pose(TrackGeometry path, double station) {
        double clamped = Math.clamp(station,0,path.getLength()); var p = path.sample(clamped);
        return station == clamped ? p : new TrackPose(add(p.position(),p.tangent(),station-clamped),
                p.tangent(),p.lateral(),p.normal(),0,p.gauge());
    }
    public static TrackVector edge(TrackGeometry path, double station, double offset) {
        var p = pose(path,station); return add(p.position(),p.lateral(),offset);
    }

    public static NativeRailCurve reference(TrackGeometry path, double from, double to, double offset,
                                            TrackVector start, TrackVector end, double startYaw, double endYaw, double scale) {
        double length = to-from, reach = Math.min(2*scale,length/2);
        var a = pose(path,from); var b = pose(path,to); var zero = new TrackVector(0,0,0);
        double sa = 1-offset*a.curvature(), sb = 1-offset*b.curvature();
        var da = add(start,add(a.position(),a.lateral(),offset),-1);
        var db = add(end,add(b.position(),b.lateral(),offset),-1);
        var va = add(new TrackVector(Math.sin(startYaw)*sa,0,Math.cos(startYaw)*sa),a.tangent(),-sa);
        var vb = add(new TrackVector(Math.sin(endYaw)*sb,0,Math.cos(endYaw)*sb),b.tangent(),-sb);
        var head = new NativeRailCurve(List.of(new NativeRailCurve.Knot(0,da,va,zero),new NativeRailCurve.Knot(reach,zero,zero,zero)));
        var tail = new NativeRailCurve(List.of(new NativeRailCurve.Knot(0,zero,zero,zero),new NativeRailCurve.Knot(reach,db,vb,zero)));
        var stations = new TreeSet<Double>(); stations.add(0.0); stations.add(length); stations.add(reach); stations.add(length-reach);
        int samples = Math.max(1,(int)Math.ceil(length/(0.1*scale)));
        for (int i = 1; i < samples; i++) stations.add(length*i/samples);
        var knots = new ArrayList<NativeRailCurve.Knot>(stations.size());
        for (double distance : stations) {
            double s = from+distance; var sample = pose(path,s); double curvature = sample.curvature(), speed = 1-offset*curvature;
            double lo = s-.001*scale, hi = s+.001*scale;
            double derivative = (pose(path,hi).curvature()-pose(path,lo).curvature())/(hi-lo);
            var point = add(sample.position(),sample.lateral(),offset);
            var first = multiply(sample.tangent(),speed);
            var second = add(multiply(sample.lateral(),curvature*speed),sample.tangent(),-offset*derivative);
            var correction = distance < reach ? head.at(distance) : distance > length-reach ? tail.at(distance-(length-reach)) : null;
            if (correction != null) {
                point = add(point,correction.point(),1); first = add(first,correction.first(),1); second = add(second,correction.second(),1);
            }
            knots.add(new NativeRailCurve.Knot(distance,point,first,second));
        }
        return new NativeRailCurve(knots);
    }

    private record Frame(TrackVector point, TrackVector lateral, TrackVector normal) {}
    private static Frame frame(NativeRailCurve curve, double distance) {
        var k = curve.at(distance); var tangent = multiply(k.first(),1/Math.sqrt(dot(k.first(),k.first())));
        var lateral = cross(curve.up(),tangent); lateral = multiply(lateral,1/Math.sqrt(dot(lateral,lateral)));
        return new Frame(k.point(),lateral,cross(tangent,lateral));
    }
    /** Envelope corners bound the authored 22-point rail profile, including the rail foot. */
    public static List<Double> cuts(NativeRailCurve curve, double scale) {
        return cuts(curve,scale,0,0);
    }
    public static List<Double> cuts(NativeRailCurve curve, double scale, double startMiter, double endMiter) {
        try (var stage = NativeLayoutProfile.stage("geometry/fixedRailSegments")) {
            var frames = new HashMap<Double,Frame>();
            var result = new ArrayList<Double>(); double length = curve.knots().getLast().distance(); result.add(0.0);
            double[] maxError = {0,0};
            int count = Math.max(1,(int)Math.ceil(length/(MAX_LENGTH*scale)));
            for (int i = 0; i < count; i++) split(curve,length*i/count,length*(i+1)/count,scale,
                    i == 0 ? startMiter : 0,i+1 == count ? endMiter : 0,frames,result,maxError);
            if (stage != null) {
                stage.value("segments",result.size()-1); stage.value("maxLength",MAX_LENGTH*scale);
                stage.value("maxSampleError",maxError[0]); stage.value("maxStripLength",maxError[1]);
                stage.value("errorLimit",ERROR*scale); stage.value("sampleFrames",frames.size());
            }
            return result;
        }
    }
    private static void split(NativeRailCurve curve, double from, double to, double scale, double startMiter, double endMiter, HashMap<Double,Frame> frames,
                              List<Double> output, double[] maxError) {
        var a = frames.computeIfAbsent(from,s -> frame(curve,s)); var b = frames.computeIfAbsent(to,s -> frame(curve,s));
        double error = 0, span = 0;
        for (int corner = 0; corner < 4; corner++) {
            double x = ((corner&1) == 0 ? -1 : 1)*HALF_WIDTH*scale, y = -(corner/2)*HEIGHT*scale;
            var ca = startMiter == 0 ? a : frames.computeIfAbsent(from-x*startMiter,s -> frame(curve,s));
            var cb = endMiter == 0 ? b : frames.computeIfAbsent(to+x*endMiter,s -> frame(curve,s));
            double dx = cb.point().x()-ca.point().x()+x*(cb.lateral().x()-ca.lateral().x())+y*(cb.normal().x()-ca.normal().x());
            double dy = cb.point().y()-ca.point().y()+x*(cb.lateral().y()-ca.lateral().y())+y*(cb.normal().y()-ca.normal().y());
            double dz = cb.point().z()-ca.point().z()+x*(cb.lateral().z()-ca.lateral().z())+y*(cb.normal().z()-ca.normal().z());
            span = Math.max(span,dx*dx+dy*dy+dz*dz);
        }
        // These samples select strip boundaries only; they never become mesh vertices.
        int count = Math.max(4,(int)Math.ceil((to-from)/(.1*scale)));
        for (int i = 1; i < count; i++) {
            double t = (double)i/count, station = from+(to-from)*t;
            var actual = frames.computeIfAbsent(station,s -> frame(curve,s));
            double px = actual.point().x()-(1-t)*a.point().x()-t*b.point().x();
            double py = actual.point().y()-(1-t)*a.point().y()-t*b.point().y();
            double pz = actual.point().z()-(1-t)*a.point().z()-t*b.point().z();
            double lx = actual.lateral().x()-(1-t)*a.lateral().x()-t*b.lateral().x();
            double ly = actual.lateral().y()-(1-t)*a.lateral().y()-t*b.lateral().y();
            double lz = actual.lateral().z()-(1-t)*a.lateral().z()-t*b.lateral().z();
            double nx = actual.normal().x()-(1-t)*a.normal().x()-t*b.normal().x();
            double ny = actual.normal().y()-(1-t)*a.normal().y()-t*b.normal().y();
            double nz = actual.normal().z()-(1-t)*a.normal().z()-t*b.normal().z();
            for (int corner = 0; corner < 4; corner++) {
                double x = ((corner&1) == 0 ? -1 : 1)*HALF_WIDTH*scale, y = -(corner/2)*HEIGHT*scale;
                double dx = px+x*lx+y*nx, dy = py+x*ly+y*ny, dz = pz+x*lz+y*nz;
                if (startMiter != 0 || endMiter != 0) {
                    var ca = frames.computeIfAbsent(from-x*startMiter,s -> frame(curve,s));
                    var cb = frames.computeIfAbsent(to+x*endMiter,s -> frame(curve,s));
                    var current = frames.computeIfAbsent(station+x*(-(1-t)*startMiter+t*endMiter),s -> frame(curve,s));
                    dx = current.point().x()+x*current.lateral().x()+y*current.normal().x()
                            -(1-t)*(ca.point().x()+x*ca.lateral().x()+y*ca.normal().x())-t*(cb.point().x()+x*cb.lateral().x()+y*cb.normal().x());
                    dy = current.point().y()+x*current.lateral().y()+y*current.normal().y()
                            -(1-t)*(ca.point().y()+x*ca.lateral().y()+y*ca.normal().y())-t*(cb.point().y()+x*cb.lateral().y()+y*cb.normal().y());
                    dz = current.point().z()+x*current.lateral().z()+y*current.normal().z()
                            -(1-t)*(ca.point().z()+x*ca.lateral().z()+y*ca.normal().z())-t*(cb.point().z()+x*cb.lateral().z()+y*cb.normal().z());
                }
                error = Math.max(error,dx*dx+dy*dy+dz*dz);
            }
        }
        double limit = (MAX_LENGTH+1e-6)*scale;
        if (!Double.isFinite(error) || !Double.isFinite(span))
            throw new IllegalArgumentException("Invalid fixed rail frame: from="+from+", to="+to+", startMiter="+startMiter+", endMiter="+endMiter);
        if (error > ERROR*ERROR*scale*scale || span > limit*limit) {
            double middle = (from+to)/2;
            if (to-from <= 1e-6*scale || middle == from || middle == to)
                throw new IllegalArgumentException("Fixed rail cannot meet strip budget: from="+from+", to="+to+", error="+Math.sqrt(error)+", length="+Math.sqrt(span));
            split(curve,from,middle,scale,startMiter,0,frames,output,maxError);
            split(curve,middle,to,scale,0,endMiter,frames,output,maxError);
        } else { output.add(to); maxError[0] = Math.max(maxError[0],Math.sqrt(error)); maxError[1] = Math.max(maxError[1],Math.sqrt(span)); }
    }
}
