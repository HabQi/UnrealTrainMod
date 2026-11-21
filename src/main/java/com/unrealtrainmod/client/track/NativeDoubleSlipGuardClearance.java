/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.track.NativeDoubleSlipRenderState.Frame;
import com.unrealtrainmod.client.track.NativeTurnoutCurves.SlipGeometry;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout.Blade;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout.Control;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout.Link;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.Motion;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Trim only the visible through-blade toe against the baked guards, before registering any static pages. */
final class NativeDoubleSlipGuardClearance {
    private static final int SWEEP_STEPS = 128;
    private static final double CLEARANCE = .001;
    private NativeDoubleSlipGuardClearance() {}

    static List<Control> resolve(NativeDoubleSlipLayout layout, Map<String,List<Source>> originals, List<Source> guards) {
        var obstacles = guards.stream().map(source -> Guard.of(source.mesh())).toList();
        var controls = new ArrayList<Control>();
        for (var control : layout.controls()) {
            var blades = control.blades();
            var stock = trim(blades.get(1),control.name(),layout,originals,obstacles);
            var spread = NativeDoubleSlipLayout.spreadThroughOpening(stock);
            var through = spread == stock ? stock : trim(spread,control.name(),layout,originals,obstacles);
            controls.add(new Control(control.name(),List.of(blades.getFirst(),through,blades.getLast())));
        }
        return List.copyOf(controls);
    }

    private static Blade trim(Blade blade, String control, NativeDoubleSlipLayout layout,
                              Map<String,List<Source>> originals, List<Guard> guards) {
        var links = blade.links();
        double scale = layout.scale();
        Motion motion = null;
        double speed = 0;
        for (int i = links.size()-1; i >= 0; i--) {
            var link = links.get(i);
            if (link.visibleFrom() == 1) break;
            var frame = Frame.of(link.strip(),scale,link.visibleFrom());
            motion = frame.motion(motion,link.rotation(),blade.opensInNormal());
            double rotation = Math.abs(link.rotation());
            speed += frame.length()*rotation;
            double radius = frame.length()+.18*scale;
            // The speed bound covers any point on the link between its nearest samples.
            double padding = CLEARANCE*scale+(speed+radius*rotation)/(2*SWEEP_STEPS);
            // Enclose the link throughout motion before deforming the source mesh or testing any triangles.
            double reach = radius+speed;
            var p = frame.start();
            var o = layout.origin();
            var candidates = new Box(p.x()-o.x()-reach,p.y()-o.y()-.18*scale,p.z()-o.z()-reach,
                    p.x()-o.x()+reach,p.y()-o.y(),p.z()-o.z()+reach);
            var near = guards.stream().filter(g -> g.bounds().overlaps(candidates,padding)).toList();
            if (near.isEmpty()) continue;

            double last = link.visibleFrom();
            for (var source : originals.get(frame.shape().side() > 0 ? "railL" : "railR")) {
                var rail = NativeTurnoutCurves.slipGeometry(source.mesh(),frame.shape());
                last = Math.max(last,endOfOverlap(rail,motion,layout,near,padding));
            }
            if (last <= link.visibleFrom()) continue;
            if (i == links.size()-1)
                throw new IllegalArgumentException("Double slip guard reaches the fixed heel of "+control);

            var trimmed = new ArrayList<>(links);
            for (int j = 0; j < i; j++) {
                var hidden = links.get(j);
                trimmed.set(j,new Link(hidden.strip(),hidden.rotation(),1));
            }
            trimmed.set(i,new Link(link.strip(),link.rotation(),Math.min(1,Math.nextUp((float)last))));
            return new Blade(List.copyOf(trimmed),blade.opensInNormal());
        }
        return blade;
    }

    private static double endOfOverlap(SlipGeometry rail, Motion motion, NativeDoubleSlipLayout layout,
                                       List<Guard> guards, double padding) {
        var mesh = rail.mesh();
        var data = mesh.vertices();
        var origin = layout.origin();
        double scale = layout.scale();
        var moved = new Vector4d[rail.sections().length];
        for (int i = 0; i < moved.length; i++) moved[i] = new Vector4d();
        var polygon = new Vector4d[14];
        var clipped = new Vector4d[14];
        for (int i = 0; i < polygon.length; i++) {
            polygon[i] = new Vector4d();
            clipped[i] = new Vector4d();
        }
        var indices = mesh.indices();
        double farthest = -1;
        for (int step = 0; step <= SWEEP_STEPS; step++) {
            float phase = step/(float)SWEEP_STEPS;
            TrackVector start = motion.start(phase);
            double x = start.x()-origin.x(), y = start.y()-origin.y(), z = start.z()-origin.z();
            double yaw = motion.direction(phase), c = Math.cos(yaw)*scale, s = Math.sin(yaw)*scale;
            for (int i = 0; i < moved.length; i++) {
                int at = i*8;
                moved[i].set(x+c*data[at]+s*data[at+2],y+scale*data[at+1],
                        z+c*data[at+2]-s*data[at],rail.sections()[i]);
            }
            for (int i = 0; i < indices.length; i += 3) {
                var a = moved[indices[i]]; var b = moved[indices[i+1]]; var d = moved[indices[i+2]];
                if (Math.max(a.w,Math.max(b.w,d.w)) <= farthest) continue;
                var bounds = Box.of(a.x,a.y,a.z,b.x,b.y,b.z,d.x,d.y,d.z);
                for (var guard : guards) {
                    if (!guard.bounds().overlaps(bounds,padding)) continue;
                    for (var triangle : guard.triangles()) {
                        if (!triangle.bounds().overlaps(bounds,padding)) continue;
                        polygon[0].set(a); polygon[1].set(b); polygon[2].set(d);
                        farthest = Math.max(farthest,overlap(triangle.planes(),padding,polygon,clipped));
                    }
                }
            }
        }
        return farthest;
    }

    /** A padded triangular prism encloses the guarded surface between sampled poses. */
    private static double overlap(List<Plane> planes, double padding, Vector4d[] polygon, Vector4d[] clipped) {
        int count = 3;
        for (var plane : planes) {
            int size = 0;
            var a = polygon[count-1];
            double da = plane.distance(a)-padding;
            for (int i = 0; i < count; i++) {
                var b = polygon[i];
                double db = plane.distance(b)-padding;
                if ((da < 0 && db > 0) || (da > 0 && db < 0)) a.lerp(b,da/(da-db),clipped[size++]);
                if (db <= 0) clipped[size++].set(b);
                a = b;
                da = db;
            }
            if (size == 0) return -1;
            var swap = polygon;
            polygon = clipped;
            clipped = swap;
            count = size;
        }
        double farthest = -1;
        for (int i = 0; i < count; i++) farthest = Math.max(farthest,polygon[i].w);
        return farthest;
    }

    private record Plane(double x, double y, double z, double limit) {
        static Plane of(Vector3d normal, Vector3d point) {
            return new Plane(normal.x,normal.y,normal.z,normal.dot(point));
        }
        double distance(Vector4d point) { return x*point.x+y*point.y+z*point.z-limit; }
    }

    private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        static Box of(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
            return new Box(Math.min(ax,Math.min(bx,cx)),Math.min(ay,Math.min(by,cy)),Math.min(az,Math.min(bz,cz)),
                    Math.max(ax,Math.max(bx,cx)),Math.max(ay,Math.max(by,cy)),Math.max(az,Math.max(bz,cz)));
        }
        Box union(Box other) {
            return new Box(Math.min(minX,other.minX),Math.min(minY,other.minY),Math.min(minZ,other.minZ),
                    Math.max(maxX,other.maxX),Math.max(maxY,other.maxY),Math.max(maxZ,other.maxZ));
        }
        boolean overlaps(Box other, double padding) {
            return minX <= other.maxX+padding && maxX+padding >= other.minX
                    && minY <= other.maxY+padding && maxY+padding >= other.minY
                    && minZ <= other.maxZ+padding && maxZ+padding >= other.minZ;
        }
    }

    private record Obstacle(Box bounds, List<Plane> planes) {
        static Obstacle of(Vector3d a, Vector3d b, Vector3d c) {
            var ab = b.sub(a,new Vector3d());
            var bc = c.sub(b,new Vector3d());
            var ca = a.sub(c,new Vector3d());
            var normal = ab.cross(bc,new Vector3d());
            if (normal.lengthSquared() < 1e-24) return null;
            normal.normalize();
            var bounds = Box.of(a.x,a.y,a.z,b.x,b.y,b.z,c.x,c.y,c.z);
            // The padded AABB also bounds the corners of very narrow triangles.
            return new Obstacle(bounds,List.of(Plane.of(normal,a),Plane.of(normal.negate(new Vector3d()),a),
                    Plane.of(ab.cross(normal).normalize(),a),Plane.of(bc.cross(normal).normalize(),b),
                    Plane.of(ca.cross(normal).normalize(),c),new Plane(-1,0,0,-bounds.minX),
                    new Plane(1,0,0,bounds.maxX),new Plane(0,-1,0,-bounds.minY),new Plane(0,1,0,bounds.maxY),
                    new Plane(0,0,-1,-bounds.minZ),new Plane(0,0,1,bounds.maxZ)));
        }
    }

    private record Guard(Box bounds, List<Obstacle> triangles) {
        static Guard of(IndexedMesh mesh) {
            var data = mesh.vertices();
            var indices = mesh.indices();
            var triangles = new ArrayList<Obstacle>();
            for (int i = 0; i < indices.length; i += 3) {
                var triangle = Obstacle.of(vertex(data,indices[i]),vertex(data,indices[i+1]),vertex(data,indices[i+2]));
                if (triangle != null) triangles.add(triangle);
            }
            return new Guard(triangles.stream().map(Obstacle::bounds).reduce(Box::union).orElseThrow(),triangles);
        }
        private static Vector3d vertex(float[] data, int index) {
            int at = index*8;
            return new Vector3d(data[at],data[at+1],data[at+2]);
        }
    }
}
