/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.mesh.NativeLayoutProfile;
import com.unrealtrainmod.track.mesh.NativeFixedRails;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.RailMeshAccumulator;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import com.unrealtrainmod.model.IndexedMesh;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Rigid source expansion occurs once for static pages, never by sampling a path per vertex. */
final class NativeTurnoutMeshes {
    private NativeTurnoutMeshes() {}
    record PreparedPart(NativeTurnoutLayout.Part part, List<Source> sources) {}

    static Map<NativeTurnoutLayout.Part, List<Source>> prepare(NativeTurnoutLayout layout, Map<String, List<Source>> originals) {
        var result = new LinkedHashMap<NativeTurnoutLayout.Part, List<Source>>();
        try (var stage = NativeLayoutProfile.stage("turnout/prepareSources")) {
            if (stage != null) stage.value("parts", layout.parts().size());
            for (var part : layout.parts()) {
                var originalsForPart = originals.get(part.name());
                if (originalsForPart == null) throw new IllegalArgumentException("Missing native r11 part: " + part.name());
                var sources = new ArrayList<Source>(); result.put(part, sources);
                for (var source : originalsForPart) sources.add(part.bladeLength() > 0
                        ? NativeTurnoutModels.blade(source, originals.get(part.name().equals("L1") ? "railL" : "railR").getFirst(), part.bladeLength(), part.bladeContact())
                        : part.railBend() != null ? (part.moving()
                                ? NativeTurnoutModels.rail(source, (float)part.visibleLength(), part.railBend())
                                : NativeTurnoutModels.fixedRail(source, part.visibleLength(), part.railBend())) : NativeTurnoutModels.retain(source));
            }
        } catch (RuntimeException | Error exception) {
            release(result); throw exception;
        }
        return Map.copyOf(result);
    }

    static void release(Map<NativeTurnoutLayout.Part, List<Source>> sources) {
        for (var parts : sources.values()) for (var source : parts) NativeTurnoutModels.release(source);
    }

    static Map<Long, List<PreparedPart>> fixedParts(NativeTurnoutLayout layout, Map<NativeTurnoutLayout.Part, List<Source>> sources) {
        var result = new LinkedHashMap<Long, List<PreparedPart>>();
        for (var part : layout.parts()) if (!part.moving()) {
            var prepared = new PreparedPart(part,sources.get(part));
            var chunks = fixedRail(part) ? railChunks(layout.origin(),part.transform(layout.origin(),layout.scale(),0),prepared.sources(),layout.scale())
                    : List.of(chunk(part.position()));
            for (long key : chunks) result.computeIfAbsent(key,ignored -> new ArrayList<>()).add(prepared);
        }
        result.replaceAll((chunk, parts) -> List.copyOf(parts));
        return Map.copyOf(result);
    }

    private static TrackVector origin(NativeTurnoutLayout layout, Long chunk) {
        return chunk == null ? layout.origin() : new TrackVector(ChunkPos.getX(chunk)*16.0,
                Math.floor(layout.origin().y()/16)*16, ChunkPos.getZ(chunk)*16.0);
    }
    static RailTrackMeshData build(NativeTurnoutLayout layout, Map<NativeTurnoutLayout.Part, List<Source>> sources,
                                   Long chunk, boolean includeMoving, float movement) {
        var selected = new ArrayList<PreparedPart>();
        for (var part : layout.parts()) if ((includeMoving || !part.moving()) && (chunk == null || chunk(part.position()) == chunk.longValue()))
            selected.add(new PreparedPart(part, sources.get(part)));
        return build(layout, selected, chunk, movement);
    }
    static RailTrackMeshData build(NativeTurnoutLayout layout, List<PreparedPart> parts, Long chunk, float movement) {
        var origin = origin(layout, chunk); var output = new RailMeshAccumulator(origin);
        var position = new Vector3f(); var normal = new Vector3f();
        for (var prepared : parts) {
            var part = prepared.part(); var transform = part.transform(origin, layout.scale(), movement);
            for (var source : prepared.sources()) {
                var mesh = part.railBend() == null && !part.moving() && part.visibleLength() > 0
                        ? trim(source.mesh(), (float)(-part.visibleLength()/2-0.0005), (float)(part.visibleLength()/2+0.0005)) : source.mesh();
                if (chunk != null && fixedRail(part)) appendRailChunk(output,source,mesh,transform,position,normal);
                else append(output, source, mesh, transform, position, normal);
            }
        }
        return output.finish();
    }
    static RailTrackMeshData endpoint(NativeTurnoutLayout layout, long chunk, List<TrackPartSnapshot.Part> parts) {
        var origin = origin(layout, chunk); var output = new RailMeshAccumulator(origin);
        var position = new Vector3f(); var normal = new Vector3f();
        for (var part : parts) {
            var transform = new Matrix4f().translation((float)(layout.origin().x()-origin.x()),
                    (float)(layout.origin().y()-origin.y()), (float)(layout.origin().z()-origin.z())).mul(part.transform());
            append(output, part.source(), part.source().mesh(), transform, position, normal);
        }
        return output.finish();
    }
    static void append(RailMeshAccumulator output, Source source, IndexedMesh mesh, Matrix4f transform,
                               Vector3f position, Vector3f normal) {
        var normals = transform.normal(new Matrix3f());
        output.append(source.texture(), source.doubleSided(), mesh, (vertex, values) -> {
            int v = vertex*8; var data = mesh.vertices();
            transform.transformPosition(position.set(data[v], data[v+1], data[v+2]));
            normals.transform(normal.set(data[v+5], data[v+6], data[v+7]));
            if (normal.lengthSquared() > 0) normal.normalize();
            values.add(position.x); values.add(position.y); values.add(position.z);
            values.add(data[v+3]); values.add(data[v+4]); values.add(normal.x); values.add(normal.y); values.add(normal.z);
        });
    }

    static List<PreparedPart> movingParts(NativeTurnoutLayout layout, Map<NativeTurnoutLayout.Part, List<Source>> sources) {
        var result = new ArrayList<PreparedPart>();
        for (var part : layout.parts()) if (part.moving()) result.add(new PreparedPart(part, sources.get(part)));
        return List.copyOf(result);
    }

    static TrackPartSnapshot moving(NativeTurnoutLayout layout, List<PreparedPart> parts, float movement) {
        var result = new ArrayList<TrackPartSnapshot.Part>();
        for (var part : parts) {
            var matrix = part.part().transform(layout.origin(), layout.scale(), movement);
            for (var source : part.sources()) result.add(new TrackPartSnapshot.Part(source, matrix, true));
        }
        return new TrackPartSnapshot(result);
    }

    static long chunk(TrackVector position) {
        return ChunkPos.asLong((int)Math.floor(position.x() / 16), (int)Math.floor(position.z() / 16));
    }

    private static boolean fixedRail(NativeTurnoutLayout.Part part) {
        return part.railBend() != null && (part.name().equals("railL") || part.name().equals("railR"));
    }
    static List<Long> railChunks(TrackVector origin, Matrix4f transform, List<Source> sources, double scale) {
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        var point = new Vector3f();
        for (var source : sources) {
            var vertices = source.mesh().vertices();
            for (int i = 0; i < vertices.length; i += 8) {
                transform.transformPosition(point.set(vertices[i],vertices[i+1],vertices[i+2]));
                minX = Math.min(minX,origin.x()+point.x); maxX = Math.max(maxX,origin.x()+point.x);
                minZ = Math.min(minZ,origin.z()+point.z); maxZ = Math.max(maxZ,origin.z()+point.z);
            }
        }
        // A fixed strip is at most 3 m plus its cross section. Reject corrupt finite
        // positions before converting them into an unbounded chunk enumeration.
        double limit = (NativeFixedRails.MAX_LENGTH+.5)*scale;
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
                || maxX-minX > limit || maxZ-minZ > limit
                || minX/16 < Integer.MIN_VALUE || maxX/16 >= Integer.MAX_VALUE
                || minZ/16 < Integer.MIN_VALUE || maxZ/16 >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid fixed rail bounds: parts="+sources.stream().map(Source::part).toList()
                    +", x=["+minX+","+maxX+"], z=["+minZ+","+maxZ+"]");
        var chunks = new ArrayList<Long>();
        for (long x = (long)Math.floor(minX/16), lastX = (long)Math.floor(maxX/16); x <= lastX; x++)
            for (long z = (long)Math.floor(minZ/16), lastZ = (long)Math.floor(maxZ/16); z <= lastZ; z++) chunks.add(ChunkPos.asLong((int)x,(int)z));
        return chunks;
    }
    static void appendRailChunk(RailMeshAccumulator output, Source source, IndexedMesh mesh, Matrix4f transform, Vector3f position, Vector3f normal) {
        var data = mesh.vertices().clone(); var normals = transform.normal(new Matrix3f());
        float minX = Float.POSITIVE_INFINITY, minZ = minX, maxX = Float.NEGATIVE_INFINITY, maxZ = maxX;
        for (int i = 0; i < data.length; i += 8) {
            transform.transformPosition(position.set(data[i],data[i+1],data[i+2]));
            normals.transform(normal.set(data[i+5],data[i+6],data[i+7]));
            if (normal.lengthSquared() > 0) normal.normalize();
            data[i] = position.x; data[i+1] = position.y; data[i+2] = position.z;
            data[i+5] = normal.x; data[i+6] = normal.y; data[i+7] = normal.z;
            minX = Math.min(minX,position.x); maxX = Math.max(maxX,position.x);
            minZ = Math.min(minZ,position.z); maxZ = Math.max(maxZ,position.z);
        }
        var result = new IndexedMesh(data,mesh.colors(),mesh.indices());
        if (minX < 0 || maxX > 16) result = NativeRailClip.clipAxis(result,0,0,16);
        if (minZ < 0 || maxZ > 16) result = NativeRailClip.clipAxis(result,2,0,16);
        var kept = new IntArrayList(result.indices().length); var vertices = result.vertices();
        for (int i = 0; i < result.indices().length; i += 3) {
            int a = result.indices()[i]*8, b = result.indices()[i+1]*8, c = result.indices()[i+2]*8;
            // A face exactly on the upper boundary belongs to the neighboring chunk only.
            if (vertices[a] == 16 && vertices[b] == 16 && vertices[c] == 16
                    || vertices[a+2] == 16 && vertices[b+2] == 16 && vertices[c+2] == 16) continue;
            double ux = vertices[b]-vertices[a], uy = vertices[b+1]-vertices[a+1], uz = vertices[b+2]-vertices[a+2];
            double vx = vertices[c]-vertices[a], vy = vertices[c+1]-vertices[a+1], vz = vertices[c+2]-vertices[a+2];
            double nx = uy*vz-uz*vy, ny = uz*vx-ux*vz, nz = ux*vy-uy*vx;
            if (nx*nx+ny*ny+nz*nz < 1e-22) continue;
            kept.add(result.indices()[i]); kept.add(result.indices()[i+1]); kept.add(result.indices()[i+2]);
        }
        var clipped = new IndexedMesh(vertices,result.colors(),kept.toIntArray());
        output.append(source.texture(),source.doubleSided(),clipped,(index,values) -> {
            for (int i = 0; i < 8; i++) values.add(clipped.vertices()[index*8+i]);
        });
    }

    record Corner(float[] values, int color) {}
    private record Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int color) {}

    static IndexedMesh join(List<IndexedMesh> sections) {
        var vertices = new FloatArrayList(); var colors = new IntArrayList(); var indices = new IntArrayList();
        var lookup = new HashMap<Vertex, Integer>();
        for (var section : sections) {
            var data = section.vertices();
            for (int index : section.indices()) {
                int v = index * 8, color = section.colors()[index];
                var key = new Vertex(data[v], data[v + 1], data[v + 2], data[v + 3], data[v + 4], data[v + 5], data[v + 6], data[v + 7], color);
                Integer target = lookup.get(key);
                if (target == null) {
                    target = colors.size(); lookup.put(key, target); colors.add(color);
                    for (int i = 0; i < 8; i++) vertices.add(data[v + i]);
                }
                indices.add(target.intValue());
            }
        }
        return new IndexedMesh(vertices.toFloatArray(), colors.toIntArray(), indices.toIntArray());
    }

    /** Fixed pages later expand triangles for chunk cuts; cross-instance vertex hashing is wasted here. */
    static IndexedMesh concatenate(List<IndexedMesh> sections) {
        int vertexCount = 0, indexCount = 0;
        for (var section : sections) { vertexCount += section.colors().length; indexCount += section.indices().length; }
        var vertices = new float[vertexCount*8]; var colors = new int[vertexCount]; var indices = new int[indexCount];
        int vertex = 0, index = 0;
        for (var section : sections) {
            System.arraycopy(section.vertices(),0,vertices,vertex*8,section.vertices().length);
            System.arraycopy(section.colors(),0,colors,vertex,section.colors().length);
            for (int value : section.indices()) indices[index++] = value+vertex;
            vertex += section.colors().length;
        }
        return new IndexedMesh(vertices,colors,indices);
    }

    static Corner interpolate(Corner previous, Corner current, float fraction) {
        float[] values = new float[previous.values().length];
        for (int i = 0; i < values.length; i++) values[i] = previous.values()[i] + (current.values()[i] - previous.values()[i]) * fraction;
        int color = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = previous.color() >>> shift & 255, b = current.color() >>> shift & 255;
            color |= Math.round(a + (b - a) * fraction) << shift;
        }
        return new Corner(values, color);
    }

    /** Called only by source preparation or static page baking; never by animation submission. */
    static IndexedMesh trim(IndexedMesh mesh, float start, float end) {
        return NativeRailClip.clip(mesh, start, end);
    }

    /** Static rail/wing joint: clip in a linear projection, then restore source coordinates. */
    static IndexedMesh miter(IndexedMesh mesh, float center, float joint, float slope, boolean forward) {
        float direction = forward ? 1 : -1;
        var vertices = mesh.vertices().clone();
        for (int i = 0; i < vertices.length; i += 8)
            vertices[i+2] = direction*(vertices[i+2]-joint)+slope*(vertices[i]-center);
        var result = trim(new IndexedMesh(vertices, mesh.colors(), mesh.indices()), 0, Float.POSITIVE_INFINITY);
        var data = result.vertices();
        for (int i = 0; i < data.length; i += 8)
            data[i+2] = joint+direction*(data[i+2]-slope*(data[i]-center));
        return result;
    }
}
