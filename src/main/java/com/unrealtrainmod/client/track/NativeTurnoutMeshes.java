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
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Rigid source expansion occurs once for static pages, never by sampling a path per vertex. */
final class NativeTurnoutMeshes {
    private NativeTurnoutMeshes() {}

    static RailTrackMeshData build(NativeTurnoutLayout layout, Map<String, List<Source>> sources,
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
            var partSources = sources.get(part.name());
            if (partSources == null) throw new IllegalArgumentException("Missing native r11 part: " + part.name());
            for (var source : partSources) {
                var mesh = part.visibleLength() > 0 ? trim(source.mesh(), part.visibleLength()) : source.mesh();
                output.append(source.texture(), source.doubleSided(), mesh, (vertex, values) -> {
                    int v = vertex * 8;
                    float[] data = mesh.vertices();
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

    static TrackPartSnapshot moving(NativeTurnoutLayout layout, Map<String, List<Source>> sources, float movement) {
        var result = new ArrayList<TrackPartSnapshot.Part>();
        for (var part : layout.parts()) if (part.moving()) {
            var matrix = part.transform(layout.origin(), layout.scale(), movement);
            for (var source : sources.get(part.name())) result.add(new TrackPartSnapshot.Part(source, matrix, true));
        }
        return new TrackPartSnapshot(result);
    }

    static long chunk(TrackVector position) {
        return ChunkPos.asLong((int)Math.floor(position.x() / 16), (int)Math.floor(position.z() / 16));
    }

    private record Corner(float[] values, int color) {}

    /** Trim fixed connecting rails once while baking a page; moving sources remain shared and unmodified. */
    private static IndexedMesh trim(IndexedMesh mesh, double length) {
        float limit = (float)(length / 2 + 0.01);
        var vertices = new FloatArrayList(); var colors = new IntArrayList(); var indices = new IntArrayList();
        for (int triangle = 0; triangle < mesh.indices().length; triangle += 3) {
            var polygon = new ArrayList<Corner>(3);
            for (int corner = 0; corner < 3; corner++) {
                int index = mesh.indices()[triangle + corner];
                polygon.add(new Corner(java.util.Arrays.copyOfRange(mesh.vertices(), index * 8, index * 8 + 8), mesh.colors()[index]));
            }
            var clipped = clip(clip(polygon, -limit, true), limit, false);
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
                float[] values = new float[8];
                for (int i = 0; i < 8; i++) values[i] = previous.values()[i] + (current.values()[i] - previous.values()[i]) * fraction;
                values[2] = plane;
                int color = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int a = previous.color() >>> shift & 255, b = current.color() >>> shift & 255;
                    color |= Math.round(a + (b - a) * fraction) << shift;
                }
                output.add(new Corner(values, color));
            }
            if (inside) output.add(current);
            previous = current; previousInside = inside;
        }
        return output;
    }
}
