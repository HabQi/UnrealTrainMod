/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.payload.TrackChunkPayload;
import com.unrealtrainmod.network.payload.TrackChunkUnloadPayload;
import com.unrealtrainmod.network.payload.TrackSegmentRemovePayload;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.TrackSegmentChange;
import com.unrealtrainmod.track.TrackSegmentData;
import com.unrealtrainmod.track.persistence.TrackSavedData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Subscriptions describe chunks actually sent to each player, not merely queued chunks. */
public final class TrackSyncManager {
    private static final Map<ServerLevel, LevelState> LEVELS = new IdentityHashMap<>();

    private TrackSyncManager() {}

    public static void register(IEventBus bus) {
        bus.addListener(TrackSyncManager::chunkSent);
        bus.addListener(TrackSyncManager::chunkUnwatched);
        bus.addListener(TrackSyncManager::playerLoggedOut);
        bus.addListener(TrackSyncManager::playerCloned);
        bus.addListener(TrackSyncManager::playerChangedDimension);
        bus.addListener(TrackSyncManager::levelUnloaded);
        bus.addListener(TrackSyncManager::serverStopped);
    }

    private static void chunkSent(ChunkWatchEvent.Sent event) {
        ServerPlayer player = event.getPlayer();
        if (player.serverLevel() != event.getLevel()) return;
        LevelState state = LEVELS.computeIfAbsent(event.getLevel(), LevelState::new);
        if (state.graph == null) return;
        Subscription subscription = state.players.get(player.getUUID());
        if (subscription == null || subscription.player != player) {
            subscription = new Subscription(player);
            state.players.put(player.getUUID(), subscription);
        }
        subscription.chunks.add(event.getPos().toLong());
        state.sendSnapshot(player, event.getPos());
    }

    private static void chunkUnwatched(ChunkWatchEvent.UnWatch event) {
        LevelState state = LEVELS.get(event.getLevel());
        if (state == null) return;
        Subscription subscription = state.players.get(event.getPlayer().getUUID());
        // Ignore unwatch events from the previous player object after a respawn.
        if (subscription == null || subscription.player != event.getPlayer()
                || !subscription.chunks.remove(event.getPos().toLong())) return;
        PacketDistributor.sendToPlayer(event.getPlayer(), new TrackChunkUnloadPayload(event.getLevel().dimension().location(),
                event.getPos().x, event.getPos().z));
        if (subscription.chunks.isEmpty()) state.players.remove(event.getPlayer().getUUID());
    }

    private static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        removePlayer(event.getEntity().getUUID());
    }

    private static void playerCloned(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer) removePlayer(event.getOriginal().getUUID());
    }

    private static void removePlayer(UUID id) {
        for (LevelState state : LEVELS.values()) state.players.remove(id);
    }

    private static void playerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        for (var entry : LEVELS.entrySet()) {
            if (entry.getKey().dimension().equals(event.getFrom())) entry.getValue().players.remove(event.getEntity().getUUID());
        }
    }

    private static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelState state = LEVELS.remove(level);
            if (state != null) state.close();
        }
    }

    private static void serverStopped(ServerStoppedEvent event) {
        for (LevelState state : LEVELS.values()) state.close();
        LEVELS.clear();
    }

    private static final class Subscription {
        private final ServerPlayer player;
        private final Set<Long> chunks = new HashSet<>();

        private Subscription(ServerPlayer player) { this.player = player; }
    }

    private static final class LevelState {
        private final ServerLevel level;
        private final TrackGraph graph;
        private final Map<UUID, Subscription> players = new HashMap<>();
        private final Consumer<TrackSegmentChange> listener = this::changed;

        private LevelState(ServerLevel level) {
            this.level = level;
            TrackGraph loaded;
            try {
                loaded = TrackSavedData.get(level).graph();
            } catch (IllegalStateException exception) {
                UnrealTrainMod.LOGGER.error("Track synchronization disabled for dimension {}: {}",
                        level.dimension().location(), exception.getMessage());
                loaded = null;
            }
            graph = loaded;
            // Attach only after persistence has completely restored the graph.
            if (graph != null) graph.addSegmentListener(listener);
        }

        private void sendSnapshot(ServerPlayer player, ChunkPos chunk) {
            List<TrackSegment> segments = graph.segmentsInChunk(chunk.x, chunk.z);
            int offset = 0;
            do {
                int end = Math.min(offset + TrackChunkPayload.MAX_SEGMENTS, segments.size());
                List<TrackSegmentData> batch = new ArrayList<>(end - offset);
                for (int i = offset; i < end; i++) batch.add(TrackSegmentData.from(graph, segments.get(i)));
                PacketDistributor.sendToPlayer(player, new TrackChunkPayload(level.dimension().location(),
                        chunk.x, chunk.z, offset == 0, batch));
                offset = end;
            } while (offset < segments.size());
        }

        private void changed(TrackSegmentChange change) {
            if (players.isEmpty()) return;
            TrackSegment segment = change.segment();
            boolean added = change.type() == TrackSegmentChange.Type.ADDED;
            TrackSegmentData data = added ? TrackSegmentData.from(graph, segment) : null;
            for (Subscription subscription : players.values()) {
                if (subscription.player.serverLevel() != level) continue;
                for (long chunk : subscription.chunks) {
                    int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                    if (!segment.coversChunk(x, z)) continue;
                    if (added) {
                        PacketDistributor.sendToPlayer(subscription.player, new TrackChunkPayload(level.dimension().location(),
                                x, z, false, List.of(data)));
                    } else {
                        PacketDistributor.sendToPlayer(subscription.player,
                                new TrackSegmentRemovePayload(level.dimension().location(), segment.id()));
                        break;
                    }
                }
            }
        }

        private void close() {
            if (graph != null) graph.removeSegmentListener(listener);
            players.clear();
        }
    }
}
