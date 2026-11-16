/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeDiamondLayout;
import com.unrealtrainmod.track.mesh.RailMeshAccumulator;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Immutable CPU layout and shared resource-time components. Owning caches manage all GPU pages. */
final class NativeDiamondRenderState {
    final NativeDiamondLayout layout;
    private final Map<String, List<Source>> sources;
    private final Map<Long, List<NativeDiamondLayout.Part>> chunks;

    NativeDiamondRenderState(NativeDiamondLayout layout, ClientRailModelManager.Model model) {
        this.layout = layout;
        sources = NativeTurnoutModels.sources(model.parts());
        for (var part : layout.parts()) if (!sources.containsKey(part.name())) {
            throw new IllegalArgumentException("Missing native diamond part: "+part.name());
        }
        if (!sources.containsKey("sleeper")) throw new IllegalArgumentException("Missing native diamond part: sleeper");
        var groups = new LinkedHashMap<Long, List<NativeDiamondLayout.Part>>();
        for (var part : layout.parts()) groups.computeIfAbsent(part.chunk(), ignored -> new java.util.ArrayList<>()).add(part);
        groups.replaceAll((chunk, parts) -> List.copyOf(parts));
        chunks = Map.copyOf(groups);
    }
    TrackStaticMesh build(long chunk) { return new RailTrackGpuMesh(build(layout, sources, chunk, chunks.getOrDefault(chunk, List.of()))); }
    static RailTrackMeshData build(NativeDiamondLayout layout, Map<String, List<Source>> sources, Long chunk) {
        return build(layout, sources, chunk, layout.parts());
    }
    private static RailTrackMeshData build(NativeDiamondLayout layout, Map<String, List<Source>> sources,
                                           Long chunk, List<NativeDiamondLayout.Part> parts) {
        var origin = chunk == null ? layout.origin() : new TrackVector(ChunkPos.getX(chunk)*16.0,
                Math.floor(layout.origin().y()/16)*16, ChunkPos.getZ(chunk)*16.0);
        var output = new RailMeshAccumulator(origin);
        var position = new Vector3f(); var normal = new Vector3f();
        for (var part : parts) {
            if (chunk != null && part.chunk() != chunk.longValue()) continue;
            var matrix = part.transform(origin, layout.scale());
            var normals = matrix.normal(new Matrix3f());
            for (var source : sources.get(part.name())) {
                var mesh = source.mesh();
                output.append(source.texture(), source.doubleSided(), mesh, (index, values) -> {
                    int v = index*8; var data = mesh.vertices();
                    matrix.transformPosition(position.set(data[v], data[v+1], data[v+2]));
                    normals.transform(normal.set(data[v+5], data[v+6], data[v+7]));
                    if (normal.lengthSquared() > 0) normal.normalize();
                    values.add(position.x); values.add(position.y); values.add(position.z);
                    values.add(data[v+3]); values.add(data[v+4]);
                    values.add(normal.x); values.add(normal.y); values.add(normal.z);
                });
            }
        }
        for (var run : layout.ordinary()) {
            if (chunk == null) {
                for (long key : layout.chunks()) appendOrdinary(output, origin, layout.scale(), sources.get("sleeper"), run, key);
            } else appendOrdinary(output, origin, layout.scale(), sources.get("sleeper"), run, chunk);
        }
        return output.finish();
    }

    private static void appendOrdinary(RailMeshAccumulator output, TrackVector origin, double scale,
                                       List<Source> sources, NativeDiamondLayout.OrdinaryRun run, long chunk) {
        var data = RailTrackMeshBuilder.build(run.geometry(), sources, ChunkPos.getX(chunk), ChunkPos.getZ(chunk),
                scale, NativeDiamondLayout.ORDINARY_PERIOD, RailTrackMeshBuilder.Filter.ALL);
        double x = data.origin().x()-origin.x(), y = data.origin().y()-origin.y(), z = data.origin().z()-origin.z();
        for (var batch : data.batches()) {
            var mesh = batch.mesh();
            output.append(batch.texture(), batch.doubleSided(), mesh, (index, values) -> {
                int v = index*8; var vertices = mesh.vertices();
                values.add((float)(vertices[v]+x)); values.add((float)(vertices[v+1]+y)); values.add((float)(vertices[v+2]+z));
                for (int i = 3; i < 8; i++) values.add(vertices[v+i]);
            });
        }
    }
}
