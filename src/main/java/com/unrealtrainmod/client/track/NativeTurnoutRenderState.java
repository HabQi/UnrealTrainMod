/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import org.joml.Matrix3f;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.TrackMeshBuilder;
import com.unrealtrainmod.track.mesh.RailMeshAccumulator;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One native assembly, including all control groups. No legacy tile or virtual turnout is created. */
final class NativeTurnoutRenderState implements AutoCloseable {
    private record Binding(NativeTurnoutModels.Part part, Matrix4f pose, String control) {}
    final TrackVector origin;
    final RailPartRenderer parts;
    private final NativeTurnoutModels.Lease lease;
    private final NativeTurnoutModels.Model model;
    private final List<Binding> bindings = new ArrayList<>();
    private final List<TrackPartSnapshot.Part> fixedParts = new ArrayList<>();
    final Map<Long, List<TrackRenderMesh>> chunks = new HashMap<>();
    private Map<String, Float> displayed = Map.of();
    private TrackPartSnapshot snapshot;

    NativeTurnoutRenderState(TrackAssemblyInstance assembly) {
        origin = assembly.origin();
        lease = NativeTurnoutModels.INSTANCE.acquire(assembly);
        model = NativeTurnoutModels.INSTANCE.model();
        parts = new RailPartRenderer(origin, Math.floor(origin.y() / 16) * 16);
        if (hasModels()) {
            for (var sleeper : layout().sleepers()) fixed(model.parts().get("sleeper"), frame(sleeper.pose()));
            layout().actuators().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                fixed(model.parts().get("switch_machine"), frame(entry.getValue()));
                bindings.add(new Binding(model.parts().get("throw_rod"), frame(entry.getValue()), entry.getKey()));
            });
        }
    }

    private void fixed(NativeTurnoutModels.Part part, Matrix4f pose) {
        var matrix = new Matrix4f(pose).mul(part.offset());
        for (var source : part.sources()) fixedParts.add(new TrackPartSnapshot.Part(source, matrix));
    }

    NativeTurnoutLayout layout() { return lease.layout(); }
    boolean hasModels() { return model != null && model.available(); }

    List<TrackRenderMesh> buildChunk(int x, int z) {
        var result = new ArrayList<TrackRenderMesh>();
        result.add(new TrackGpuMesh(TrackMeshBuilder.buildNative(layout(), origin, x, z, null, 0, hasModels())));
        for (String control : layout().leaves().keySet()) result.add(new TrackMorphMesh(
                TrackMeshBuilder.buildNative(layout(), origin, x, z, control, 0, hasModels()),
                TrackMeshBuilder.buildNative(layout(), origin, x, z, control, 1, hasModels())));
        return List.copyOf(result);
    }

    void updateLeaves(List<TrackRenderMesh> meshes, Map<String, Float> positions) {
        int index = 1;
        for (String control : layout().leaves().keySet()) ((TrackMorphMesh)meshes.get(index++)).movement(positions.get(control));
    }

    TrackPartSnapshot snapshot(Map<String, Float> positions) {
        if (snapshot != null && displayed.equals(positions)) return snapshot;
        var draws = new ArrayList<>(fixedParts);
        for (var binding : bindings) {
            var part = binding.part();
            var matrix = new Matrix4f(binding.pose()).mul(part.offset());
            if (binding.control() != null) {
                var move = new Vector3f(part.normal()).lerp(part.reverse(), positions.get(binding.control()));
                matrix.translate(move);
            }
            for (var source : part.sources()) draws.add(new TrackPartSnapshot.Part(source, matrix, true));
        }
        displayed = Map.copyOf(positions);
        snapshot = new TrackPartSnapshot(draws);
        return snapshot;
    }

    /** Preview parts are expanded once per changed draft, then uploaded to the normal static cache. */
    RailTrackMeshData previewParts(Map<String, Float> positions) {
        var output = new RailMeshAccumulator(origin);
        for (var part : snapshot(positions).parts()) {
            var mesh = part.source().mesh();
            var normal = part.transform().normal(new Matrix3f());
            var position = new Vector3f(); var n = new Vector3f();
            output.append(part.source().texture(), part.source().doubleSided(), mesh, (index, vertices) -> {
                int v = index * 8;
                part.transform().transformPosition(position.set(mesh.vertices()[v], mesh.vertices()[v + 1], mesh.vertices()[v + 2]));
                normal.transform(n.set(mesh.vertices()[v + 5], mesh.vertices()[v + 6], mesh.vertices()[v + 7])).normalize();
                vertices.add(position.x); vertices.add(position.y); vertices.add(position.z);
                vertices.add(mesh.vertices()[v + 3]); vertices.add(mesh.vertices()[v + 4]);
                vertices.add(n.x); vertices.add(n.y); vertices.add(n.z);
            });
        }
        return output.finish();
    }

    List<TrackStaticMesh> previewGeometry(int x, int z, Map<String, Float> positions) {
        var meshes = new ArrayList<TrackStaticMesh>();
        meshes.add(new TrackGpuMesh(TrackMeshBuilder.buildNative(layout(), origin, x, z, null, 0, hasModels())));
        for (String control : layout().leaves().keySet()) meshes.add(new TrackGpuMesh(
                TrackMeshBuilder.buildNative(layout(), origin, x, z, control, positions.get(control), hasModels())));
        return meshes;
    }

    private static Matrix4f frame(TrackPose pose) {
        var p = pose.position(); var x = pose.lateral(); var y = pose.normal(); var z = pose.tangent();
        return new Matrix4f().translation((float)p.x(), (float)p.y(), (float)p.z())
                .m00((float)x.x()).m01((float)x.y()).m02((float)x.z())
                .m10((float)y.x()).m11((float)y.y()).m12((float)y.z())
                .m20((float)z.x()).m21((float)z.y()).m22((float)z.z());
    }

    void removeChunk(long chunk) {
        var meshes = chunks.remove(chunk);
        if (meshes != null) meshes.forEach(TrackRenderMesh::close);
        parts.invalidateInstances();
    }

    void invalidateGpu() {
        chunks.values().forEach(group -> group.forEach(TrackRenderMesh::close));
        parts.invalidateInstances();
    }

    @Override public void close() {
        chunks.values().forEach(group -> group.forEach(TrackRenderMesh::close));
        chunks.clear();
        parts.close();
        lease.close();
    }
}
