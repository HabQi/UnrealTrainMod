/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.model.IndexedMesh;
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
    private final Map<NativeDiamondLayout.Part,List<Source>> fixedRails = new java.util.HashMap<>();

    NativeDiamondRenderState(NativeDiamondLayout layout, ClientRailModelManager.Model model) {
        this.layout = layout;
        sources = NativeTurnoutModels.sources(model.parts());
        for (var part : layout.parts()) if (!sources.containsKey(part.name())) {
            throw new IllegalArgumentException("Missing native diamond part: "+part.name());
        }
        if (!sources.containsKey("sleeper_support")) throw new IllegalArgumentException("Missing native diamond part: sleeper_support");
        var groups = new LinkedHashMap<Long, List<NativeDiamondLayout.Part>>();
        for (var part : layout.parts()) {
            var keys = List.of(part.chunk());
            if (isLongRail(part)) {
                var prepared = sources.get(part.name()).stream().map(source -> new Source(source.part(),source.texture(),source.doubleSided(),mesh(part,source))).toList();
                fixedRails.put(part,prepared);
                keys = NativeTurnoutMeshes.railChunks(layout.origin(),part.transform(layout.origin(),layout.scale()),prepared,layout.scale());
            }
            for (long key : keys) groups.computeIfAbsent(key,ignored -> new java.util.ArrayList<>()).add(part);
        }
        groups.replaceAll((chunk, parts) -> List.copyOf(parts));
        chunks = Map.copyOf(groups);
    }
    TrackStaticMesh build(long chunk) { return new RailTrackGpuMesh(build(layout, sources, chunk, chunks.getOrDefault(chunk, List.of()),fixedRails)); }
    RailTrackMeshData buildData(long chunk) { return build(layout, sources, chunk, chunks.getOrDefault(chunk, List.of()), fixedRails); }
    static RailTrackMeshData build(NativeDiamondLayout layout, Map<String, List<Source>> sources, Long chunk) {
        var selected = chunk == null ? layout.parts() : layout.parts().stream().filter(part -> part.chunk() == chunk.longValue()).toList();
        return build(layout, sources, chunk, selected,Map.of());
    }
    private static RailTrackMeshData build(NativeDiamondLayout layout, Map<String, List<Source>> sources,
                                           Long chunk, List<NativeDiamondLayout.Part> parts, Map<NativeDiamondLayout.Part,List<Source>> fixedRails) {
        var origin = chunk == null ? layout.origin() : new TrackVector(ChunkPos.getX(chunk)*16.0,
                Math.floor(layout.origin().y()/16)*16, ChunkPos.getZ(chunk)*16.0);
        var output = new RailMeshAccumulator(origin);
        var position = new Vector3f(); var normal = new Vector3f();
        for (var part : parts) {
            var matrix = part.transform(origin, layout.scale());
            var normals = matrix.normal(new Matrix3f());
            var cached = fixedRails.get(part);
            for (var source : cached == null ? sources.get(part.name()) : cached) {
                var prepared = cached == null ? mesh(part,source) : source.mesh();
                if (chunk != null && isLongRail(part)) {
                    NativeTurnoutMeshes.appendRailChunk(output,source,prepared,matrix,position,normal);
                    continue;
                }
                output.append(source.texture(), source.doubleSided(), prepared, (index, values) -> {
                    int v = index*8; var data = prepared.vertices();
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
                for (long key : layout.chunks()) appendOrdinary(output, origin, layout.scale(), sources.get("sleeper_support"), run, key);
            } else appendOrdinary(output, origin, layout.scale(), sources.get("sleeper_support"), run, chunk);
        }
        return output.finish();
    }

    static IndexedMesh mesh(NativeDiamondLayout.Part part, Source source) {
        if (isLongRail(part)) {
            double start = part.localZ(), end = start+part.visibleLength();
            return NativeTurnoutCurves.fixedRail(source.mesh(),start,end,part.localX(),part.curve(),
                    part.startMiter() ? part.miterSlope() : 0,part.startMiter() ? 0 : part.miterSlope());
        }
        var mesh = part.visibleLength() > 0 ? NativeTurnoutMeshes.trim(source.mesh(),
                (float)(-part.visibleLength()/2-0.0005), (float)(part.visibleLength()/2+0.0005)) : source.mesh();
        if (part.miterSlope() != 0) mesh = NativeTurnoutMeshes.miter(mesh, (float)part.localX(),
                (float)part.localZ(), (float)part.miterSlope(), part.name().equals("wingRm") || part.localZ() < 0);
        if (part.curve() != null) mesh = NativeTurnoutCurves.follow(mesh, (float)part.localZ(), part.localX(), part.curve());
        return mesh;
    }
    private static boolean isLongRail(NativeDiamondLayout.Part part) {
        return (part.name().equals("railL") || part.name().equals("railR")) && part.visibleLength() > 0 && part.curve() != null;
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
