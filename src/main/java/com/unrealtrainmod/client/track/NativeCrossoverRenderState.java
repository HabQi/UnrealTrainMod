/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.mesh.NativeCrossoverLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/** Each control owns two endpoint caches; all fixed ranges join the normal shared static pages. */
final class NativeCrossoverRenderState implements AutoCloseable {
    final NativeCrossoverLayout layout;
    NativeTurnoutLoading.Trace loading;
    final Map<String, NativeTurnoutRenderState> switches = new LinkedHashMap<>();
    private final NativeCrossoverMeshes fixed;
    NativeCrossoverRenderState(NativeCrossoverLayout layout, ClientRailModelManager.Model model, TrackStaticBuffers buffers) {
        this.layout = layout;
        loading = NativeTurnoutLoading.current();
        fixed = new NativeCrossoverMeshes(layout, model);
        try {
            fixed.prepareChunks();
            for (var entry : fixed.switches().entrySet()) {
                try (var operation = NativeTurnoutLoading.open(loading, "geometry/control/"+entry.getKey())) {
                    switches.put(entry.getKey(), new NativeTurnoutRenderState(entry.getValue(), fixed.sources(), buffers));
                }
            }
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    void attachLoading(NativeTurnoutLoading.Trace trace) {
        loading = trace; switches.values().forEach(value -> value.attachLoading(trace));
    }
    TrackStaticMesh build(long chunk) {
        try (var operation = NativeTurnoutLoading.open(loading, "geometry/"+layout.kind().label()+"FixedChunk")) {
            if (operation != null) { operation.value("chunkX", net.minecraft.world.level.ChunkPos.getX(chunk)); operation.value("chunkZ", net.minecraft.world.level.ChunkPos.getZ(chunk)); }
            var data = fixed.build(chunk); NativeTurnoutLoading.geometry(operation, data);
            var mesh = new RailTrackGpuMesh(data); NativeTurnoutLoading.associate(mesh, loading); return mesh;
        }
    }
    NativeCrossoverMeshes meshes() { return fixed; }
    void removeChunk(long chunk) {
        switches.values().forEach(value -> value.removeChunk(chunk));
    }
    void invalidateGpu() { switches.values().forEach(NativeTurnoutRenderState::invalidateGpu); }
    @Override public void close() {
        switches.values().forEach(NativeTurnoutRenderState::close); switches.clear();
        fixed.close();
    }
}
