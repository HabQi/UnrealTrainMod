/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/** CPU sources and endpoint variants survive backend changes; timelines remain control-owned. */
final class NativeTurnoutRenderState implements AutoCloseable {
    final NativeTurnoutLayout layout;
    final RailPartRenderer parts;
    NativeTurnoutLoading.Trace loading;
    private final Map<NativeTurnoutLayout.Part, List<Source>> sources;
    private final List<NativeTurnoutMeshes.PreparedPart> movingParts;
    private final Map<Long, List<NativeTurnoutMeshes.PreparedPart>> fixedParts;
    private final TrackStaticBuffers buffers;
    private final Endpoint[] endpoints = new Endpoint[2];
    record EndpointMesh(TrackStaticMesh mesh, long bytes) {}
    private static final class Endpoint {
        final TrackPartSnapshot snapshot;
        final Map<Long, List<TrackPartSnapshot.Part>> groups = new LinkedHashMap<>();
        final Map<Long, EndpointMesh> meshes = new LinkedHashMap<>();
        final Map<Long, com.unrealtrainmod.client.ModelPreparation.Ticket<com.unrealtrainmod.track.mesh.RailTrackMeshData>> pending = new LinkedHashMap<>();
        final Set<Long> failed = new java.util.HashSet<>();
        Endpoint(TrackPartSnapshot snapshot, NativeTurnoutLayout layout) {
            this.snapshot = snapshot;
            for (var part : snapshot.parts()) {
                var matrix = part.transform();
                long chunk = ChunkPos.asLong((int)Math.floor((layout.origin().x()+matrix.m30())/16),
                        (int)Math.floor((layout.origin().z()+matrix.m32())/16));
                groups.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(part);
            }
        }
    }
    TrackPartSnapshot snapshot;
    private float movement = Float.NaN;
    private long representationFrame = -1;
    private boolean merged, closed;

    NativeTurnoutRenderState(NativeTurnoutLayout layout, ClientRailModelManager.Model model) {
        this(layout, model, TrackStaticBuffers.WORLD);
    }
    NativeTurnoutRenderState(NativeTurnoutLayout layout, ClientRailModelManager.Model model, TrackStaticBuffers buffers) {
        this(layout, NativeTurnoutModels.sources(model.parts()), buffers);
    }
    NativeTurnoutRenderState(NativeTurnoutLayout layout, Map<String, List<Source>> originals, TrackStaticBuffers buffers) {
        this.layout = layout; this.buffers = buffers;
        loading = NativeTurnoutLoading.current();
        sources = NativeTurnoutMeshes.prepare(layout, originals);
        try {
            movingParts = NativeTurnoutMeshes.movingParts(layout, sources);
            fixedParts = NativeTurnoutMeshes.fixedParts(layout, sources);
        } catch (RuntimeException | Error failure) { NativeTurnoutMeshes.release(sources); throw failure; }
        parts = new RailPartRenderer(layout.origin(), Math.floor(layout.origin().y()/16)*16, true);
        parts.loading(loading);
    }
    void attachLoading(NativeTurnoutLoading.Trace trace) {
        loading = trace; parts.loading(trace);
        for (var endpoint : endpoints) if (endpoint != null) for (var mesh : endpoint.meshes.values())
            NativeTurnoutLoading.associate(mesh.mesh(), trace);
    }
    private int endpointIndex() { return movement == 0 ? 0 : movement == 1 ? 1 : -1; }
    void movement(float value) {
        if (movement == value) return;
        int index = value == 0 ? 0 : value == 1 ? 1 : -1;
        snapshot = index >= 0 && endpoints[index] != null ? endpoints[index].snapshot : NativeTurnoutMeshes.moving(layout, movingParts, value);
        if (index >= 0 && endpoints[index] == null) endpoints[index] = new Endpoint(snapshot, layout);
        movement = value; representationFrame = -1;
    }
    TrackStaticMesh build(long chunk) {
        try (var operation = NativeTurnoutLoading.open(loading, "geometry/fixedChunk")) {
            if (operation != null) { operation.value("chunkX", ChunkPos.getX(chunk)); operation.value("chunkZ", ChunkPos.getZ(chunk)); }
            var data = buildData(chunk); NativeTurnoutLoading.geometry(operation, data);
            var mesh = new RailTrackGpuMesh(data);
            NativeTurnoutLoading.associate(mesh, loading);
            return mesh;
        }
    }
    com.unrealtrainmod.track.mesh.RailTrackMeshData buildData(long chunk) {
        return NativeTurnoutMeshes.build(layout, fixedParts.getOrDefault(chunk, List.of()), chunk, 0);
    }
    private static boolean loaded(long chunk, Set<Long> chunks, ClientLevel level) {
        return chunks.contains(chunk) && level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
    }
    /** Freeze the representation before uploads: all views in this frame use the same choice. */
    boolean prepareEndpoint(long frame, Set<Long> chunks, ClientLevel level, TrackWorldRenderer.BuildBudget budget,
                            boolean shadow, TrackRenderStats stats) {
        int index = endpointIndex();
        if (representationFrame != frame) {
            representationFrame = frame; merged = index >= 0;
            if (merged) {
                var endpoint = endpoints[index];
                for (long chunk : endpoint.groups.keySet()) if (loaded(chunk, chunks, level)) {
                    var cached = endpoint.meshes.get(chunk);
                    if (cached == null || !cached.mesh().isEmpty() && !buffers.ready(cached.mesh())) { merged = false; break; }
                }
            }
        }
        if (index >= 0) {
            var endpoint = endpoints[index];
            for (var group : endpoint.groups.entrySet()) {
                long chunk = group.getKey(); if (!loaded(chunk, chunks, level)) continue;
                var cached = endpoint.meshes.get(chunk);
                boolean buildEndpoint = cached == null && !shadow && !endpoint.failed.contains(chunk);
                if (cached == null && !shadow && !buildEndpoint) NativeTurnoutLoading.budgetWait(loading);
                if (buildEndpoint) {
                    long start = System.nanoTime();
                    try (var operation = NativeTurnoutLoading.open(loading, "geometry/endpointCache")) {
                        if (operation != null) { operation.value("endpoint", index); operation.value("chunkX", ChunkPos.getX(chunk)); operation.value("chunkZ", ChunkPos.getZ(chunk)); }
                        if (!endpoint.pending.containsKey(chunk)) {
                            var captured = List.copyOf(group.getValue());
                            var ticket = com.unrealtrainmod.client.ModelPreparation.geometry(() -> NativeTurnoutMeshes.endpoint(layout, chunk, captured),
                                    TrackPreparation::size, ignored -> {});
                            if (ticket != null) {
                                endpoint.pending.put(chunk, ticket);
                                ticket.whenReady(() -> {
                                    try {
                                        var data = ticket.take();
                                        endpoint.meshes.put(chunk, new EndpointMesh(new RailTrackGpuMesh(data, TrackStaticMesh.PageDomain.ENDPOINT), TrackPreparation.size(data)));
                                        NativeTurnoutLoading.associate(endpoint.meshes.get(chunk).mesh(), loading);
                                    } catch (RuntimeException exception) {
                                        endpoint.failed.add(chunk); ticket.close();
                                        org.slf4j.LoggerFactory.getLogger(NativeTurnoutRenderState.class).warn("Cannot prepare turnout endpoint", exception);
                                    } finally { endpoint.pending.remove(chunk); }
                                });
                            }
                        }
                    } catch (RuntimeException exception) {
                        var failed = endpoint.pending.remove(chunk); if (failed != null) failed.close();
                        endpoint.failed.add(chunk);
                        org.slf4j.LoggerFactory.getLogger(NativeTurnoutRenderState.class).warn("Cannot prepare turnout endpoint", exception);
                    }
                }
                if (cached != null && !cached.mesh().isEmpty()) buffers.request(cached.mesh(), TrackRenderStats.STRUCTURE_ASSEMBLY);
            }
        }
        if (stats != null) {
            long bytes = 0;
            for (var endpoint : endpoints) if (endpoint != null) for (var cached : endpoint.meshes.values()) bytes += cached.bytes();
            stats.nativeEndpointCache(bytes, merged);
        }
        return merged;
    }
    Map<Long, EndpointMesh> endpointMeshes() { return endpoints[endpointIndex()].meshes; }
    void removeChunk(long chunk) {
        for (var endpoint : endpoints) if (endpoint != null) {
            var pending = endpoint.pending.remove(chunk); if (pending != null) pending.close();
            var mesh = endpoint.meshes.remove(chunk); if (mesh != null) buffers.remove(mesh.mesh());
        }
        representationFrame = -1; parts.removeChunk(chunk);
        if (loading != null) loading.restart("chunkRemoved");
    }
    void invalidateGpu() {
        representationFrame = -1; merged = false; parts.invalidateInstances();
        if (loading != null) loading.restart("backendInvalidated");
    }
    @Override public void close() {
        if (closed) return;
        closed = true; parts.close(); snapshot = null;
        for (int i = 0; i < endpoints.length; i++) {
            var endpoint = endpoints[i]; if (endpoint == null) continue;
            endpoint.pending.values().forEach(com.unrealtrainmod.client.ModelPreparation.Ticket::close);
            for (var cached : endpoint.meshes.values()) buffers.remove(cached.mesh());
            endpoints[i] = null;
        }
        NativeTurnoutMeshes.release(sources);
    }
}
