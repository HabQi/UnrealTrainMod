/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.ModelPreparation;
import com.unrealtrainmod.compat.rtm.script.RailStaticSnapshot;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.mesh.NativeDiamondLayout;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout;
import com.unrealtrainmod.track.mesh.NativeCrossoverLayout;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.TrackMeshBuilder;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/** Immutable inputs in, complete CPU appearances out. No worker accesses a level or a live cache. */
final class TrackPreparation {
    record Mesh(TrackMeshData normal, TrackMeshData reverse, RailTrackMeshData rail) {
        TrackRenderMesh create() {
            return rail != null ? new RailTrackGpuMesh(rail) : reverse != null
                    ? new TrackMorphMesh(normal, reverse) : new TrackGpuMesh(normal);
        }
        long bytes() {
            if (rail != null) return size(rail);
            return (long)normal.vertexCount() * 32 + (reverse == null ? 0 : (long)reverse.vertexCount() * 32);
        }
    }
    static final class Result implements AutoCloseable {
        final Map<Long, List<Mesh>> meshes = new LinkedHashMap<>();
        NativeTurnoutRenderState turnout;
        NativeCrossoverRenderState crossover;
        NativeDiamondRenderState diamond;
        NativeDoubleSlipRenderState doubleSlip;
        boolean scriptFailed;
        long bytes() {
            long bytes = 0;
            for (var group : meshes.values()) for (var mesh : group) bytes += mesh.bytes();
            return bytes;
        }
        @Override public void close() {
            if (turnout != null) turnout.close();
            if (crossover != null) crossover.close();
            if (doubleSlip != null) doubleSlip.close();
        }
    }
    private TrackPreparation() {}
    static long size(RailTrackMeshData data) {
        long bytes = 0;
        for (var batch : data.batches()) bytes += (long)batch.mesh().vertices().length * 4
                + (long)(batch.mesh().colors().length + batch.mesh().indices().length) * 4;
        return bytes;
    }
    static Set<Long> loaded(Set<Long> chunks, ClientLevel level) {
        var result = new LinkedHashSet<Long>();
        for (long chunk : chunks) if (level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) result.add(chunk);
        return Set.copyOf(result);
    }

    static boolean track(ClientTrackCache.Entry entry, ClientRailModelManager.Model model, ClientLevel level) {
        var chunks = loaded(entry.chunks, level);
        if (entry.meshes.keySet().containsAll(chunks)) return true;
        if (entry.preparation == null) {
            var geometry = entry.geometry;
            var data = entry.data;
            var snapshot = entry.staticSnapshot;
            var missing = new LinkedHashSet<>(chunks); missing.removeAll(entry.meshes.keySet());
            entry.preparation = ModelPreparation.geometry(() -> {
                var result = new Result();
                for (long chunk : missing) {
                    Mesh mesh;
                    try { mesh = ordinary(geometry, data.gauge(), model, snapshot, chunk); }
                    catch (RuntimeException exception) {
                        if (snapshot == null) throw exception;
                        result.scriptFailed = true;
                        mesh = ordinary(geometry, data.gauge(), model, null, chunk);
                    }
                    result.meshes.put(chunk, List.of(mesh));
                }
                return result;
            }, Result::bytes, Result::close);
        }
        if (entry.preparation == null || !entry.preparation.ready()) return false;
        try {
            var result = entry.preparation.take();
            for (var group : result.meshes.entrySet()) if (entry.chunks.contains(group.getKey())) {
                entry.meshes.put(group.getKey(), group.getValue().getFirst().create());
            }
            if (result.scriptFailed) {
                model.script().close(); entry.staticSnapshot = null;
                ClientTrackCache.INSTANCE.invalidateScriptedModel(model);
            }
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(TrackPreparation.class).warn("Cannot prepare track {}", entry.data.id(), exception);
            for (long chunk : chunks) entry.meshes.putIfAbsent(chunk, new TrackGpuMesh(TrackMeshData.EMPTY));
        } finally { entry.preparation.close(); entry.preparation = null; }
        return entry.meshes.keySet().containsAll(chunks);
    }

    static boolean assembly(ClientTrackCache.AssemblyEntry entry, ClientRailModelManager.Model model, ClientLevel level) {
        var chunks = loaded(entry.chunks, level);
        if (entry.meshes.keySet().containsAll(chunks)) return true;
        if (entry.preparation == null) {
            var data = entry.data;
            var geometries = entry.geometries;
            var missing = new LinkedHashSet<>(chunks); missing.removeAll(entry.meshes.keySet());
            var turnout = entry.nativeState;
            var crossover = entry.crossoverState;
            var crossoverData = crossover == null ? Map.<Long, RailTrackMeshData>of() : crossover.meshes().chunkData();
            var diamond = entry.diamondState;
            var doubleSlip = entry.doubleSlipState;
            boolean nativeFailed = entry.nativeFailed;
            // Retain the state while the worker reads its immutable fixed geometry.
            entry.preparation = ModelPreparation.geometry(() -> {
                var result = new Result();
                try (var trace = NativeTurnoutLoading.start("background", data.modelId(), data.id());
                     var operation = NativeTurnoutLoading.open(trace, "geometry/completeAssembly")) {
                    if (!nativeFailed && NativeTurnoutCatalog.contains(data.modelId())) {
                        if (model == null) throw new IllegalArgumentException("native model unavailable");
                        if (NativeTurnoutCatalog.isDoubleSlip(data.modelId())) {
                            if (doubleSlip == null) result.doubleSlip = new NativeDoubleSlipRenderState(
                                    NativeDoubleSlipLayout.build(data.geometry(), data.gauge()), model);
                            var state = doubleSlip == null ? result.doubleSlip : doubleSlip;
                            if (doubleSlip == null) missing.addAll(state.fixed.keySet());
                            for (long chunk : missing) result.meshes.put(chunk, List.of(new Mesh(null, null, state.fixedData(chunk))));
                        } else if (NativeTurnoutCatalog.isCrossover(data.modelId())) {
                            if (crossover == null) result.crossover = new NativeCrossoverRenderState(
                                    NativeCrossoverLayout.build(data.geometry(), data.gauge()), model, TrackStaticBuffers.WORLD);
                            var state = crossover == null ? result.crossover : crossover;
                            missing.addAll(state.layout.chunks());
                            for (long chunk : missing) {
                                var dataMesh = crossover == null ? state.meshes().build(chunk) : crossoverData.get(chunk);
                                if (dataMesh == null) dataMesh = new RailTrackMeshData(new com.unrealtrainmod.track.geometry.TrackVector(
                                        ChunkPos.getX(chunk) * 16.0, Math.floor(state.layout.origin().y() / 16) * 16, ChunkPos.getZ(chunk) * 16.0),
                                        new TrackMeshData.Bounds(0, 0, 0, 0, 0, 0), List.of());
                                result.meshes.put(chunk, List.of(new Mesh(null, null, dataMesh)));
                            }
                        } else if (NativeTurnoutCatalog.isDiamond(data.modelId())) {
                            if (diamond == null) result.diamond = new NativeDiamondRenderState(NativeDiamondLayout.build(data.geometry(), data.gauge()), model);
                            var state = diamond == null ? result.diamond : diamond;
                            missing.addAll(state.layout.chunks());
                            for (long chunk : missing) result.meshes.put(chunk, List.of(new Mesh(null, null, state.buildData(chunk))));
                        } else {
                            if (turnout == null) result.turnout = new NativeTurnoutRenderState(NativeTurnoutLayout.build(data.geometry(), data.gauge()), model);
                            var state = turnout == null ? result.turnout : turnout;
                            for (long chunk : missing) result.meshes.put(chunk, List.of(new Mesh(null, null, state.buildData(chunk))));
                        }
                    } else if (data.definition().type() == com.unrealtrainmod.track.assembly.TrackAssemblyType.SIMPLE_TURNOUT) {
                        for (long chunk : missing) {
                            int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                            result.meshes.put(chunk, List.of(new Mesh(TrackMeshBuilder.buildTurnout(data, x, z, false), null, null),
                                    new Mesh(TrackMeshBuilder.buildTurnout(data, x, z, true, 0), TrackMeshBuilder.buildTurnout(data, x, z, true, 1), null)));
                        }
                    } else for (long chunk : missing) {
                        var group = new ArrayList<Mesh>();
                        for (var geometry : geometries) group.add(ordinary(geometry, data.gauge(), nativeFailed ? null : model, null, chunk));
                        result.meshes.put(chunk, List.copyOf(group));
                    }
                    return result;
                } catch (RuntimeException | Error exception) {
                    // Constructors here have no GPU storage; source-release operations marshal themselves.
                    result.close(); throw exception;
                }
            }, Result::bytes, Result::close);
        }
        if (entry.preparation == null || !entry.preparation.ready()) return false;
        try {
            var result = entry.preparation.take();
            if (result.turnout != null) entry.nativeState = result.turnout;
            if (result.crossover != null) { entry.crossoverState = result.crossover; entry.chunks.addAll(result.crossover.layout.chunks()); }
            if (result.diamond != null) { entry.diamondState = result.diamond; entry.chunks.addAll(result.diamond.layout.chunks()); }
            if (result.doubleSlip != null) {
                entry.doubleSlipState = result.doubleSlip; entry.chunks.addAll(result.doubleSlip.fixed.keySet());
            }
            for (var group : result.meshes.entrySet()) if (entry.chunks.contains(group.getKey())) {
                var meshes = group.getValue().stream().map(Mesh::create).toList();
                entry.meshes.put(group.getKey(), meshes.stream().filter(mesh -> !(mesh instanceof TrackMorphMesh)).toList());
                var moving = meshes.stream().filter(mesh -> mesh instanceof TrackMorphMesh).toList();
                if (!moving.isEmpty()) entry.tongues.put(group.getKey(), moving);
            }
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(TrackPreparation.class).warn("Cannot prepare assembly {}", entry.data.id(), exception);
            if (!entry.nativeFailed && NativeTurnoutCatalog.contains(entry.data.modelId())) entry.nativeFailed = true;
            else for (long chunk : chunks) entry.meshes.putIfAbsent(chunk, List.of(new TrackGpuMesh(TrackMeshData.EMPTY)));
        } finally { entry.preparation.close(); entry.preparation = null; }
        return entry.meshes.keySet().containsAll(chunks);
    }

    private static Mesh ordinary(TrackGeometry geometry, double gauge, ClientRailModelManager.Model model,
                                 RailStaticSnapshot snapshot, long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        if (snapshot != null) return new Mesh(null, null, snapshot.build(x, z));
        if (model != null) {
            var sources = model.batches().stream().map(batch -> new RailTrackMeshBuilder.Source("", batch.texture(),
                    batch.doubleSided(), batch.mesh())).toList();
            try { return new Mesh(null, null, RailTrackMeshBuilder.build(geometry, sources, x, z,
                    model.scaleForGauge(gauge), model.period(), RailTrackMeshBuilder.Filter.ALL)); }
            catch (IllegalArgumentException exception) {
                org.slf4j.LoggerFactory.getLogger(TrackPreparation.class).warn("Cannot prepare rail model; using native geometry", exception);
            }
        }
        try { return new Mesh(TrackMeshBuilder.build(geometry, x, z), null, null); }
        catch (IllegalArgumentException exception) { return new Mesh(TrackMeshData.EMPTY, null, null); }
    }
}
