/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

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
import org.joml.Vector3f;

/** Rigid source expansion occurs once for static pages, never by sampling a path per vertex. */
final class NativeTurnoutMeshes {
    private NativeTurnoutMeshes() {}

    static Map<NativeTurnoutLayout.Part, List<Source>> prepare(NativeTurnoutLayout layout, Map<String, List<Source>> originals) {
        var result = new LinkedHashMap<NativeTurnoutLayout.Part, List<Source>>();
        try {
            for (var part : layout.parts()) {
                var originalsForPart = originals.get(part.name());
                if (originalsForPart == null) throw new IllegalArgumentException("Missing native r11 part: " + part.name());
                var sources = new ArrayList<Source>(); result.put(part, sources);
                for (var source : originalsForPart) sources.add(part.bladeLength() > 0
                        ? NativeTurnoutModels.blade(source, originals.get(part.name().equals("L1") ? "railL" : "railR").getFirst(), part.bladeLength(), part.bladeContact())
                        : part.railBend() != null ? NativeTurnoutModels.rail(source, (float)part.visibleLength(), part.railBend()) : source);
            }
        } catch (RuntimeException exception) {
            release(result); throw exception;
        }
        return Map.copyOf(result);
    }

    static void release(Map<NativeTurnoutLayout.Part, List<Source>> sources) {
        for (var parts : sources.values()) for (var source : parts) NativeTurnoutModels.release(source);
    }

    static RailTrackMeshData build(NativeTurnoutLayout layout, Map<NativeTurnoutLayout.Part, List<Source>> sources,
                                   Long chunk, boolean includeMoving, float movement) {
        var origin = chunk == null ? layout.origin() : new TrackVector(ChunkPos.getX(chunk) * 16.0,
                Math.floor(layout.origin().y() / 16) * 16, ChunkPos.getZ(chunk) * 16.0);
        var output = new RailMeshAccumulator(origin);
        var position = new Vector3f(); var normal = new Vector3f();
        for (var part : layout.parts()) {
            if (!includeMoving && part.moving()) continue;
            if (chunk != null && chunk(part.position()) != chunk.longValue()) continue;
            var transform = part.transform(origin, layout.scale(), movement);
            var normals = transform.normal(new Matrix3f());
            var partSources = sources.get(part);
            for (var source : partSources) {
                var mesh = part.railBend() == null && !part.moving() && part.visibleLength() > 0
                        ? trim(source.mesh(), (float)(-part.visibleLength() / 2 - 0.0005),
                                (float)(part.visibleLength() / 2 + 0.0005)) : source.mesh();
                if (part.bedCut() != null) mesh = NativeTurnoutBedClip.clip(mesh, part.bedCut());
                var preparedMesh = mesh;
                output.append(source.texture(), source.doubleSided(), preparedMesh, (vertex, values) -> {
                    int v = vertex * 8;
                    float[] data = preparedMesh.vertices();
                    transform.transformPosition(position.set(data[v], data[v + 1], data[v + 2]));
                    normals.transform(normal.set(data[v + 5], data[v + 6], data[v + 7]));
                    if (normal.lengthSquared() > 0) normal.normalize();
                    values.add(position.x); values.add(position.y); values.add(position.z);
                    values.add(data[v + 3]); values.add(data[v + 4]);
                    values.add(normal.x); values.add(normal.y); values.add(normal.z);
                });
            }
        }
        return output.finish();
    }

    static TrackPartSnapshot moving(NativeTurnoutLayout layout, Map<NativeTurnoutLayout.Part, List<Source>> sources, float movement) {
        var result = new ArrayList<TrackPartSnapshot.Part>();
        for (var part : layout.parts()) if (part.moving()) {
            var matrix = part.transform(layout.origin(), layout.scale(), movement);
            for (var source : sources.get(part)) result.add(new TrackPartSnapshot.Part(source, matrix, true));
        }
        return new TrackPartSnapshot(result);
    }

    static long chunk(TrackVector position) {
        return ChunkPos.asLong((int)Math.floor(position.x() / 16), (int)Math.floor(position.z() / 16));
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

    static Corner interpolate(Corner previous, Corner current, float fraction) {
        float[] values = new float[8];
        for (int i = 0; i < 8; i++) values[i] = previous.values()[i] + (current.values()[i] - previous.values()[i]) * fraction;
        int color = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = previous.color() >>> shift & 255, b = current.color() >>> shift & 255;
            color |= Math.round(a + (b - a) * fraction) << shift;
        }
        return new Corner(values, color);
    }

    /** Called only by source preparation or static page baking; never by animation submission. */
    static IndexedMesh trim(IndexedMesh mesh, float start, float end) {
        var vertices = new FloatArrayList(); var colors = new IntArrayList(); var indices = new IntArrayList();
        for (int triangle = 0; triangle < mesh.indices().length; triangle += 3) {
            var polygon = new ArrayList<Corner>(3);
            for (int corner = 0; corner < 3; corner++) {
                int index = mesh.indices()[triangle + corner];
                polygon.add(new Corner(java.util.Arrays.copyOfRange(mesh.vertices(), index * 8, index * 8 + 8), mesh.colors()[index]));
            }
            var clipped = clip(clip(polygon, start, true), end, false);
            int first = colors.size();
            for (var corner : clipped) {
                for (float value : corner.values()) vertices.add(value);
                colors.add(corner.color());
            }
            for (int i = 1; i + 1 < clipped.size(); i++) { indices.add(first); indices.add(first + i); indices.add(first + i + 1); }
        }
        return new IndexedMesh(vertices.toFloatArray(), colors.toIntArray(), indices.toIntArray());
    }
    private static List<Corner> clip(List<Corner> input, float plane, boolean keepAbove) {
        var output = new ArrayList<Corner>();
        if (input.isEmpty()) return output;
        var previous = input.getLast(); boolean previousInside = keepAbove ? previous.values()[2] >= plane : previous.values()[2] <= plane;
        for (var current : input) {
            boolean inside = keepAbove ? current.values()[2] >= plane : current.values()[2] <= plane;
            if (inside != previousInside) {
                float fraction = (plane - previous.values()[2]) / (current.values()[2] - previous.values()[2]);
                var corner = interpolate(previous, current, fraction);
                corner.values()[2] = plane;
                output.add(corner);
            }
            if (inside) output.add(current);
            previous = current; previousInside = inside;
        }
        return output;
    }
}
