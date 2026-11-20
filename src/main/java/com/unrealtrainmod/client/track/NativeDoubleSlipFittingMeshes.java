/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.track.NativeTurnoutMeshes.Corner;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipFittings;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Attribute-preserving static cuts around the full swept envelope, before static page registration. */
final class NativeDoubleSlipFittingMeshes {
    // Trim the end overlaps at matching cross sections before joining neighboring r11 ballast tiles.
    private static final float BED_LENGTH = 1.5f;
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

    static List<Source> bake(NativeDoubleSlipLayout layout, Map<String,List<Source>> originals) {
        var motion = layout.fittings().motion().stream().map(clearance -> Cut.of(clearance,layout.origin())).toList();
        var hardware = new ArrayList<>(motion);
        for (var clearance : NativeDoubleSlipFittings.railheads(layout.fixed(),layout.scale()))
            hardware.add(Cut.of(clearance,layout.origin()));
        var result = new ArrayList<Source>();
        var tiles = new java.util.IdentityHashMap<IndexedMesh,IndexedMesh>();
        for (var fitting : layout.fittings().parts()) {
            var part = fitting.part();
            var transform = fitting.transform(layout.origin(),layout.scale());
            var cuts = fitting.hardware() ? hardware : motion;
            var sources = originals.get(part.name());
            if (sources == null) throw new IllegalArgumentException("Missing native double slip part: " + part.name());
            for (var source : sources) {
                IndexedMesh transformed;
                if (fitting.bed() != null) {
                    var tile = tiles.computeIfAbsent(source.mesh(),mesh -> NativeTurnoutMeshes.trim(mesh,0,BED_LENGTH));
                    transformed = bed(tile,fitting.bed(),layout.origin(),layout.scale());
                } else {
                    // Extract one centered sleeper from the authored support module before orienting each row.
                    var mesh = part.name().equals("sleeper_support")
                            ? tiles.computeIfAbsent(source.mesh(),sourceMesh -> NativeTurnoutMeshes.trim(sourceMesh,-.25f,.25f))
                            : NativeDiamondRenderState.mesh(part,source);
                    transformed = transform(mesh,transform);
                }
                var mesh = trim(transformed,cuts);
                if (mesh.indices().length != 0) result.add(new Source(source.part(),source.texture(),source.doubleSided(),mesh));
            }
        }
        return List.copyOf(result);
    }

    private static IndexedMesh bed(IndexedMesh mesh, NativeDoubleSlipFittings.BedSpan span,
                                   TrackVector origin, double scale) {
        var start = span.start();
        var end = span.end();
        double dx = end.center().x()-start.center().x(), dz = end.center().z()-start.center().z();
        double lx = end.lateral().x()-start.lateral().x(), lz = end.lateral().z()-start.lateral().z();
        var vertices = mesh.vertices().clone();
        var normal = new Vector3f();
        for (int i = 0; i < vertices.length; i += 8) {
            double t = vertices[i+2]/BED_LENGTH;
            double x = vertices[i]*scale;
            double rx = start.lateral().x()+t*lx, rz = start.lateral().z()+t*lz;
            vertices[i] = (float)(start.center().x()-origin.x()+t*dx+x*rx);
            vertices[i+1] = (float)(start.center().y()-origin.y()+vertices[i+1]*scale);
            vertices[i+2] = (float)(start.center().z()-origin.z()+t*dz+x*rz);
            double fx = (dx+x*lx)/(BED_LENGTH*scale), fz = (dz+x*lz)/(BED_LENGTH*scale);
            double nx = vertices[i+5], nz = vertices[i+7];
            normal.set((float)(fz*nx-rz*nz),(float)((rx*fz-rz*fx)*vertices[i+6]),(float)(rx*nz-fx*nx));
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
