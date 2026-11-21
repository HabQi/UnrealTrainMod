/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.track.NativeTurnoutMeshes.Corner;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeDiamondLayout;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipFittings;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Static fitting geometry and fixed railhead clearance for the guards. */
final class NativeDoubleSlipFittingMeshes {
    private NativeDoubleSlipFittingMeshes() {}

    private record Plane(double x, double y, double z, double limit) {
        double distance(Corner vertex) {
            var p = vertex.values();
            return x*p[0]+y*p[1]+z*p[2]-limit;
        }
    }

    private record Bounds(double minX, double minZ, double maxX, double maxY, double maxZ) {
        boolean intersects(Bounds other, double floor) {
            return maxY > floor && minX < other.maxX && maxX > other.minX && minZ < other.maxZ && maxZ > other.minZ;
        }
    }

    private record Cut(Bounds bounds, double floor, List<Plane> planes) {
        static Cut of(NativeDoubleSlipFittings.Clearance clearance, TrackVector origin) {
            var hull = clearance.hull();
            double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX;
            double floor = clearance.floor()-origin.y();
            var planes = new ArrayList<Plane>();
            planes.add(new Plane(0,1,0,floor));
            for (int i = 0; i < hull.size(); i++) {
                var a = hull.get(i); var b = hull.get((i+1)%hull.size());
                double x = a.x()-origin.x(), z = a.z()-origin.z();
                minX = Math.min(minX,x); minZ = Math.min(minZ,z);
                maxX = Math.max(maxX,x); maxZ = Math.max(maxZ,z);
                double dx = b.x()-a.x(), dz = b.z()-a.z();
                double length = Math.hypot(dx,dz);
                double nx = -dz/length, nz = dx/length;
                planes.add(new Plane(nx,0,nz,nx*x+nz*z));
            }
            return new Cut(new Bounds(minX,minZ,maxX,Double.POSITIVE_INFINITY,maxZ),floor,List.copyOf(planes));
        }
    }

    record Prepared(NativeDoubleSlipLayout layout, List<Source> sources) {}

    static Prepared prepare(NativeDoubleSlipLayout layout, Map<String,List<Source>> originals) {
        var fixed = NativeDoubleSlipFittings.railheads(layout.fixed(),layout.scale()).stream()
                .map(clearance -> Cut.of(clearance,layout.origin())).toList();
        var result = new ArrayList<Source>();
        for (var fitting : layout.fittings().parts()) {
            if (!fitting.guard()) continue;
            var part = fitting.part();
            var transform = fitting.transform(layout.origin(),layout.scale());
            for (var source : sources(originals,part.name()))
                append(result,source,trim(transform(NativeDiamondRenderState.mesh(part,source),transform),fixed));
        }
        var controls = NativeDoubleSlipGuardClearance.resolve(layout,originals,result);
        for (var fitting : layout.fittings().parts()) {
            if (fitting.guard()) continue;
            var part = fitting.part();
            var transform = fitting.transform(layout.origin(),layout.scale());
            var span = fitting.bed();
            for (var source : sources(originals,part.name())) {
                var transformed = span != null ? bed(source.mesh(),span,layout.origin(),layout.scale())
                        : transform(source.mesh(),transform);
                append(result,source,transformed);
            }
        }
        return new Prepared(new NativeDoubleSlipLayout(layout.origin(),layout.scale(),layout.fixed(),controls,layout.fittings()),
                List.copyOf(result));
    }

    private static List<Source> sources(Map<String,List<Source>> originals, String part) {
        var sources = originals.get(part);
        if (sources == null) throw new IllegalArgumentException("Missing native double slip part: " + part);
        return sources;
    }

    private static void append(List<Source> output, Source source, IndexedMesh mesh) {
        if (mesh.indices().length != 0) output.add(new Source(source.part(),source.texture(),source.doubleSided(),mesh));
    }

    private static IndexedMesh bed(IndexedMesh mesh, NativeDoubleSlipFittings.BedSpan span,
                                   TrackVector origin, double scale) {
        var crossSections = new HashMap<Float,TrackPose>();
        var vertices = mesh.vertices().clone();
        var normal = new Vector3f();
        for (int i = 0; i < vertices.length; i += 8) {
            var pose = crossSections.computeIfAbsent(vertices[i+2], z -> span.sample(z*scale));
            var point = pose.position();
            var tangent = pose.tangent();
            double rx = pose.lateral().x(), rz = pose.lateral().z();
            double x = vertices[i]*scale;
            vertices[i] = (float)(point.x()-origin.x()+x*rx);
            vertices[i+1] = (float)(point.y()-origin.y()+vertices[i+1]*scale-span.drop());
            vertices[i+2] = (float)(point.z()-origin.z()+x*rz);
            double stretch = 1-x*pose.curvature();
            double fx = tangent.x()*stretch, fy = tangent.y(), fz = tangent.z()*stretch;
            double nx = vertices[i+5], ny = vertices[i+6], nz = vertices[i+7];
            normal.set((float)(fz*nx+fy*rz*ny-rz*nz),(float)((rx*fz-rz*fx)*ny),
                    (float)(rx*nz-fx*nx-fy*rx*ny));
            if (normal.lengthSquared() > 0) normal.normalize();
            vertices[i+5] = normal.x; vertices[i+6] = normal.y; vertices[i+7] = normal.z;
        }
        return new IndexedMesh(vertices,mesh.colors(),mesh.indices());
    }

    private static IndexedMesh transform(IndexedMesh mesh, Matrix4f transform) {
        var normals = transform.normal(new Matrix3f());
        var position = new Vector3f(); var normal = new Vector3f();
        var data = mesh.vertices().clone();
        for (int i = 0; i < data.length; i += 8) {
            transform.transformPosition(position.set(data[i],data[i+1],data[i+2]));
            normals.transform(normal.set(data[i+5],data[i+6],data[i+7]));
            if (normal.lengthSquared() > 0) normal.normalize();
            data[i] = position.x; data[i+1] = position.y; data[i+2] = position.z;
            data[i+5] = normal.x; data[i+6] = normal.y; data[i+7] = normal.z;
        }
        return new IndexedMesh(data,mesh.colors(),mesh.indices());
    }

    private static IndexedMesh trim(IndexedMesh mesh, List<Cut> cuts) {
        var vertices = mesh.vertices();
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX, maxY = -minX;
        for (int i = 0; i < vertices.length; i += 8) {
            minX = Math.min(minX,vertices[i]); minZ = Math.min(minZ,vertices[i+2]);
            maxX = Math.max(maxX,vertices[i]); maxZ = Math.max(maxZ,vertices[i+2]);
            maxY = Math.max(maxY,vertices[i+1]);
        }
        var bounds = new Bounds(minX,minZ,maxX,maxY,maxZ);
        var relevant = cuts.stream().filter(cut -> bounds.intersects(cut.bounds(),cut.floor())).toList();
        if (relevant.isEmpty()) return mesh;

        var original = new Corner[mesh.colors().length];
        for (int i = 0; i < original.length; i++) original[i] =
                new Corner(java.util.Arrays.copyOfRange(vertices,i*8,i*8+8),mesh.colors()[i]);
        var output = new FloatArrayList();
        var color = new IntArrayList(); var indices = new IntArrayList();
        for (int i = 0; i < mesh.indices().length; i += 3) {
            List<List<Corner>> polygons = List.of(List.of(original[mesh.indices()[i]],
                    original[mesh.indices()[i+1]],original[mesh.indices()[i+2]]));
            for (var cut : relevant) {
                var kept = new ArrayList<List<Corner>>();
                for (var polygon : polygons) subtract(polygon,cut,kept);
                polygons = kept;
                if (polygons.isEmpty()) break;
            }
            for (var polygon : polygons) {
                int start = color.size();
                for (var vertex : polygon) {
                    output.addElements(output.size(),vertex.values());
                    color.add(vertex.color());
                }
                for (int j = 2; j < polygon.size(); j++) {
                    indices.add(start); indices.add(start+j-1); indices.add(start+j);
                }
            }
        }
        return new IndexedMesh(output.toFloatArray(),color.toIntArray(),indices.toIntArray());
    }

    private static void subtract(List<Corner> polygon, Cut cut, List<List<Corner>> output) {
        for (var plane : cut.planes()) {
            if (polygon.stream().allMatch(vertex -> plane.distance(vertex) <= 0)) {
                output.add(polygon);
                return;
            }
        }
        // Keep the portion outside each successive face; only the final prism interior is discarded.
        var inside = polygon;
        for (var plane : cut.planes()) {
            if (inside.size() < 3) break;
            if (inside.stream().allMatch(vertex -> plane.distance(vertex) >= 0)) continue;
            var outside = new ArrayList<Corner>(); var next = new ArrayList<Corner>();
            var previous = inside.getLast();
            double before = plane.distance(previous);
            for (var current : inside) {
                double after = plane.distance(current);
                if (before*after < 0) {
                    var hit = NativeTurnoutMeshes.interpolate(previous,current,(float)(before/(before-after)));
                    outside.add(hit); next.add(hit);
                }
                if (after <= 0) outside.add(current);
                if (after >= 0) next.add(current);
                before = after; previous = current;
            }
            if (outside.size() >= 3) output.add(outside);
            inside = next;
        }
    }
}
