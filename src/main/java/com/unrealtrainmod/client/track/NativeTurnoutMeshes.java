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
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Rigid source expansion occurs once for static pages, never by sampling a path per vertex. */
final class NativeTurnoutMeshes {
    private NativeTurnoutMeshes() {}
    record PreparedPart(NativeTurnoutLayout.Part part, List<Source> sources) {}

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

    static Map<Long, List<PreparedPart>> fixedParts(NativeTurnoutLayout layout, Map<NativeTurnoutLayout.Part, List<Source>> sources) {
        var result = new LinkedHashMap<Long, List<PreparedPart>>();
        for (var part : layout.parts()) if (!part.moving()) result.computeIfAbsent(chunk(part.position()), ignored -> new ArrayList<>())
                .add(new PreparedPart(part, sources.get(part)));
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
                append(output, source, mesh, transform, position, normal);
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
    private static void append(RailMeshAccumulator output, Source source, IndexedMesh mesh, Matrix4f transform,
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
        return NativeRailSlices.split(mesh, List.of(start, end)).getFirst();
    }
}
