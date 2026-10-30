/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrackChunkPayload;
import com.unrealtrainmod.network.payload.TrackChunkUnloadPayload;
import com.unrealtrainmod.network.payload.TrackSegmentRemovePayload;
import com.unrealtrainmod.track.TrackSegmentData;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.network.payload.TrackAssemblyPayload;
import com.unrealtrainmod.network.payload.TrackAssemblyStatePayload;
import com.unrealtrainmod.network.payload.TrackAssemblyRemovePayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Client-thread cache. A UUID has one geometry and one mesh per owning chunk, regardless of packet duplicates. */
public final class ClientTrackCache implements ResourceManagerReloadListener, AutoCloseable {
    public static final ClientTrackCache INSTANCE = new ClientTrackCache();
    final Map<UUID, Entry> entries = new LinkedHashMap<>();
    final Map<Long, Set<UUID>> chunks = new LinkedHashMap<>();
    final Map<UUID, AssemblyEntry> assemblies = new LinkedHashMap<>();
    private final Map<UUID, TrackAssemblyStatePayload> states = new HashMap<>();
    private final Set<UUID> deletedAssemblies = new HashSet<>();
    private ClientLevel world;

    private ClientTrackCache() {}

    public void onSync(TrackSyncEvent event) {
        if (event.payload() instanceof TrackAssemblyPayload payload && accepts(payload.dimension())) {
            var data = payload.assembly();
            if (deletedAssemblies.contains(data.id())) return;
            var latest = states.get(data.id());
            if (latest != null && latest.revision() > data.revision()) data = state(data, latest);
            var entry = assemblies.get(data.id());
            if (entry == null) { entry = new AssemblyEntry(data); assemblies.put(data.id(), entry); }
            else if (data.revision() > entry.data.revision()) entry.setState(data);
            entry.chunks.add(ChunkPos.asLong(payload.chunkX(), payload.chunkZ()));
            states.put(data.id(), new TrackAssemblyStatePayload(payload.dimension(), data.id(), entry.data.state(), entry.data.revision()));
        } else if (event.payload() instanceof TrackAssemblyStatePayload payload && accepts(payload.dimension())) {
            if (deletedAssemblies.contains(payload.id())) return;
            var latest = states.get(payload.id());
            if (latest != null && latest.revision() >= payload.revision()) return;
            states.put(payload.id(), payload);
            var entry = assemblies.get(payload.id());
            if (entry != null) entry.setState(state(entry.data, payload));
        } else if (event.payload() instanceof TrackAssemblyRemovePayload payload && accepts(payload.dimension())) {
            deletedAssemblies.add(payload.id()); states.remove(payload.id());
            var entry = assemblies.remove(payload.id());
            if (entry != null) entry.close();
        } else if (event.payload() instanceof TrackChunkPayload payload && accepts(payload.dimension())) {
            long chunk = ChunkPos.asLong(payload.chunkX(), payload.chunkZ());
            if (payload.reset()) removeChunk(chunk);
            if (payload.segments().isEmpty()) return;
            Set<UUID> members = chunks.computeIfAbsent(chunk, ignored -> new LinkedHashSet<>());
            for (TrackSegmentData data : payload.segments()) {
                Entry entry = entries.get(data.id());
                if (entry == null) {
                    entry = new Entry(data);
                    entries.put(data.id(), entry);
                } else if (!entry.data.equals(data)) {
                    entry.replace(data);
                }
                members.add(data.id());
                entry.chunks.add(chunk);
            }
        } else if (event.payload() instanceof TrackChunkUnloadPayload payload && accepts(payload.dimension())) {
            removeChunk(ChunkPos.asLong(payload.chunkX(), payload.chunkZ()));
        } else if (event.payload() instanceof TrackSegmentRemovePayload payload && accepts(payload.dimension())) {
            removeSegment(payload.segmentId());
        }
    }

    private boolean accepts(ResourceLocation dimension) {
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null || !current.dimension().location().equals(dimension)) return false;
        if (world != current) setWorld(current);
        return true;
    }

    private void removeChunk(long chunk) {
        var iterator = assemblies.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            entry.chunks.remove(chunk);
            var mesh = entry.meshes.remove(chunk); if (mesh != null) mesh.close();
            var tongue = entry.tongues.remove(chunk); if (tongue != null) tongue.close();
            if (entry.chunks.isEmpty()) { entry.close(); iterator.remove(); }
        }
        Set<UUID> members = chunks.remove(chunk);
        if (members == null) return;
        for (UUID id : members) {
            Entry entry = entries.get(id);
            entry.chunks.remove(chunk);
            TrackRenderMesh mesh = entry.meshes.remove(chunk);
            if (mesh != null) mesh.close();
            if (entry.chunks.isEmpty()) {
                entry.close();
                entries.remove(id);
            }
        }
    }

    private void removeSegment(UUID id) {
        Entry entry = entries.remove(id);
        if (entry == null) return;
        for (long chunk : entry.chunks) {
            Set<UUID> members = chunks.get(chunk);
            members.remove(id);
            if (members.isEmpty()) chunks.remove(chunk);
        }
        entry.close();
    }

    public void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level) setWorld(level);
    }

    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == world) close();
    }

    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }

    // Same-dimension player clones keep the ClientLevel and its chunk cache; do not clear on Clone.
    private void setWorld(ClientLevel level) {
        close();
        world = level;
    }

    ClientLevel world() { return world; }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        invalidateMeshes();
    }

    public void invalidateMeshes() {
        for (Entry entry : entries.values()) entry.close();
        for (var entry : assemblies.values()) entry.close();
    }

    @Override
    public void close() {
        for (Entry entry : entries.values()) entry.close();
        for (var entry : assemblies.values()) entry.close();
        assemblies.clear(); states.clear(); deletedAssemblies.clear();
        entries.clear();
        chunks.clear();
        world = null;
    }

    private static TrackAssemblyInstance state(TrackAssemblyInstance value, TrackAssemblyStatePayload state) {
        return value.withState(state.state(), state.revision());
    }

    static final class AssemblyEntry implements AutoCloseable {
        TrackAssemblyInstance data;
        final Set<Long> chunks = new LinkedHashSet<>();
        final Map<Long, TrackRenderMesh> meshes = new HashMap<>();
        final Map<Long, TrackRenderMesh> tongues = new HashMap<>();
        AssemblyEntry(TrackAssemblyInstance data) { this.data = data; }
        void setState(TrackAssemblyInstance data) {
            for (var mesh : tongues.values()) mesh.close();
            tongues.clear(); this.data = data;
        }
        @Override public void close() {
            for (var mesh : meshes.values()) mesh.close();
            for (var mesh : tongues.values()) mesh.close();
            meshes.clear(); tongues.clear();
        }
    }

    static final class Entry implements AutoCloseable {
        TrackSegmentData data;
        TrackGeometry geometry;
        final Set<Long> chunks = new HashSet<>();
        final Map<Long, TrackRenderMesh> meshes = new HashMap<>();
        boolean missingReported;

        private Entry(TrackSegmentData data) {
            this.data = data;
            geometry = data.createGeometry();
        }

        private void replace(TrackSegmentData data) {
            TrackGeometry updated = data.createGeometry();
            close();
            this.data = data;
            geometry = updated;
            missingReported = false;
        }

        AABB broadBounds(int chunkX, int chunkZ) {
            double padding = (data.gauge() + 0.9) / 2 + 0.4;
            var model = ClientRailModelManager.INSTANCE.model(data.modelId());
            if (model != null) padding = Math.max(padding, model.padding() * model.scaleForGauge(data.gauge()));
            double x = (double)chunkX * 16, z = (double)chunkZ * 16;
            double verticalPadding = model == null ? 0.4 : Math.max(0.4, padding);
            double minY = Math.min(data.start().y(), data.end().y());
            double maxY = Math.max(data.start().y(), data.end().y());
            for (var control : data.verticalControls()) {
                minY = Math.min(minY, control.z());
                maxY = Math.max(maxY, control.z());
            }
            return new AABB(x - padding, minY - verticalPadding, z - padding,
                    x + 16 + padding, maxY + verticalPadding, z + 16 + padding);
        }

        @Override
        public void close() {
            for (TrackRenderMesh mesh : meshes.values()) mesh.close();
            meshes.clear();
        }
    }
}
