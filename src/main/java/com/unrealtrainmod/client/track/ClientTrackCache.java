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
    private ClientLevel world;

    private ClientTrackCache() {}

    public void onSync(TrackSyncEvent event) {
        if (event.payload() instanceof TrackChunkPayload payload && accepts(payload.dimension())) {
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
        Set<UUID> members = chunks.remove(chunk);
        if (members == null) return;
        for (UUID id : members) {
            Entry entry = entries.get(id);
            entry.chunks.remove(chunk);
            TrackGpuMesh mesh = entry.meshes.remove(chunk);
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
        for (Entry entry : entries.values()) for (TrackGpuMesh mesh : entry.meshes.values()) mesh.close();
    }

    @Override
    public void close() {
        for (Entry entry : entries.values()) entry.close();
        entries.clear();
        chunks.clear();
        world = null;
    }

    static final class Entry implements AutoCloseable {
        private TrackSegmentData data;
        TrackGeometry geometry;
        final Set<Long> chunks = new HashSet<>();
        final Map<Long, TrackGpuMesh> meshes = new HashMap<>();

        private Entry(TrackSegmentData data) {
            this.data = data;
            geometry = data.createGeometry();
        }

        private void replace(TrackSegmentData data) {
            TrackGeometry updated = data.createGeometry();
            close();
            this.data = data;
            geometry = updated;
        }

        AABB broadBounds(int chunkX, int chunkZ) {
            double padding = (data.gauge() + 0.9) / 2 + 0.4;
            double x = (double)chunkX * 16, z = (double)chunkZ * 16;
            return new AABB(x - padding, Math.min(data.start().y(), data.end().y()) - 0.4, z - padding,
                    x + 16 + padding, Math.max(data.start().y(), data.end().y()) + 0.4, z + 16 + padding);
        }

        @Override
        public void close() {
            for (TrackGpuMesh mesh : meshes.values()) mesh.close();
            meshes.clear();
        }
    }
}
