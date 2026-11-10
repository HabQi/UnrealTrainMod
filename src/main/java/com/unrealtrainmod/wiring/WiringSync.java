/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class WiringSync {
    private record Subscription(ServerPlayer player, Map<Long, CompoundTag> chunks) {}
    private static final Map<ServerLevel, Map<UUID, Subscription>> LEVELS = new IdentityHashMap<>();
    private WiringSync() {}

    public static void register(IEventBus bus) {
        bus.addListener(WiringSync::sent); bus.addListener(WiringSync::unwatch);
        bus.addListener(WiringSync::logout); bus.addListener(WiringSync::dimension); bus.addListener(WiringSync::clonePlayer);
        bus.addListener(WiringSync::unload); bus.addListener(WiringSync::stopped);
    }
    private static void sent(ChunkWatchEvent.Sent event) {
        var level = event.getLevel(); var player = event.getPlayer();
        if (player.serverLevel() != level) return;
        var users = LEVELS.computeIfAbsent(level, l -> new HashMap<>());
        var user = users.get(player.getUUID());
        if (user == null || user.player != player) {
            user = new Subscription(player, new HashMap<>()); users.put(player.getUUID(), user);
        }
        var tag = snapshot(WiringSavedData.get(level), event.getPos());
        user.chunks.put(event.getPos().toLong(), tag); send(player, event.getPos().toLong(), tag);
    }
    private static void unwatch(ChunkWatchEvent.UnWatch event) {
        var users = LEVELS.get(event.getLevel()); if (users == null) return;
        var user = users.get(event.getPlayer().getUUID());
        if (user != null && user.player == event.getPlayer() && user.chunks.remove(event.getPos().toLong()) != null) {
            PacketDistributor.sendToPlayer(user.player, new WiringPayloads.Chunk(event.getLevel().dimension().location(),
                    event.getPos().x, event.getPos().z, true, true, true, new CompoundTag()));
        }
    }
    public static void changed(ServerLevel level) {
        var data = WiringSavedData.get(level);
        var dirty = data.takeChangedChunks();
        var users = LEVELS.get(level); if (users == null || dirty.isEmpty()) return;
        // Compute each watched chunk only once even when several players watch it.
        var snapshots = new HashMap<Long, CompoundTag>();
        for (var user : users.values()) if (user.player.serverLevel() == level) {
            user.chunks.replaceAll((chunk, previous) -> {
                if (!dirty.contains(chunk)) return previous;
                var next = snapshots.computeIfAbsent(chunk, c -> snapshot(data, new ChunkPos(c)));
                if (!next.equals(previous)) send(user.player, chunk, next);
                return next;
            });
        }
    }
    private static CompoundTag snapshot(WiringSavedData data, ChunkPos chunk) {
        var nodes = new LinkedHashMap<UUID, ConnectorData>(); var edges = new ListTag();
        for (var node : data.connectorsInChunk(chunk.toLong())) nodes.put(node.id(), node);
        for (var wire : data.wiresInChunk(chunk.toLong())) {
            var a = data.connector(wire.first()); var b = data.connector(wire.second());
            nodes.put(a.id(), a); nodes.put(b.id(), b); edges.add(wire.save());
        }
        var tag = new CompoundTag(); var list = new ListTag(); nodes.values().forEach(n -> list.add(n.save()));
        tag.put("Connectors", list); tag.put("Wires", edges); return tag;
    }
    private static void send(ServerPlayer player, long chunk, CompoundTag tag) {
        var nodes = tag.getList("Connectors", 10); var wires = tag.getList("Wires", 10);
        int count = Math.max(nodes.size(), wires.size());
        for (int start = 0; start < Math.max(1, count); start += 128) {
            var part = new CompoundTag(); var ns = new ListTag(); var ws = new ListTag();
            for (int i = start; i < Math.min(start + 128, nodes.size()); i++) ns.add(nodes.get(i).copy());
            for (int i = start; i < Math.min(start + 128, wires.size()); i++) ws.add(wires.get(i).copy());
            part.put("Connectors", ns); part.put("Wires", ws);
            PacketDistributor.sendToPlayer(player, new WiringPayloads.Chunk(player.level().dimension().location(),
                    ChunkPos.getX(chunk), ChunkPos.getZ(chunk), start == 0, start + 128 >= count, false, part));
        }
    }
    private static void remove(UUID player) { LEVELS.values().forEach(users -> users.remove(player)); }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent e) { remove(e.getEntity().getUUID()); }
    private static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) { remove(e.getEntity().getUUID()); }
    private static void clonePlayer(PlayerEvent.Clone e) { remove(e.getOriginal().getUUID()); }
    private static void unload(LevelEvent.Unload e) { if (e.getLevel() instanceof ServerLevel l) LEVELS.remove(l); }
    private static void stopped(ServerStoppedEvent e) { LEVELS.clear(); }
}
