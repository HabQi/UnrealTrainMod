/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Authoritative wiring state; entity/chunk unloading never deletes connections. */
public final class WiringSavedData extends SavedData {
    private static final String NAME = "unrealtrainmod_wiring";
    private static final Factory<WiringSavedData> FACTORY = new Factory<>(WiringSavedData::new, (tag, registries) -> load(tag));
    private final Map<UUID, ConnectorData> connectors = new LinkedHashMap<>();
    private final Map<UUID, WireData> wires = new LinkedHashMap<>();
    private final Map<Long, java.util.Set<UUID>> nodeChunks = new java.util.HashMap<>(), wireChunks = new java.util.HashMap<>();
    private final Map<UUID, java.util.Set<UUID>> incident = new java.util.HashMap<>();
    private final java.util.Set<Long> changedChunks = new java.util.HashSet<>();
    private final Map<net.minecraft.core.BlockPos, java.util.Set<UUID>> origins = new java.util.HashMap<>();

    public static WiringSavedData get(ServerLevel level) {
        var storage = level.getDataStorage();
        var data = storage.get(FACTORY, NAME);
        if (data != null) return data;
        var file = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                .resolve("data").resolve(NAME + ".dat");
        if (!Files.notExists(file)) throw new IllegalStateException("Cannot read wiring storage: " + file);
        data = new WiringSavedData(); storage.set(NAME, data); return data;
    }

    public Collection<ConnectorData> connectors() { return java.util.Collections.unmodifiableCollection(connectors.values()); }
    public Collection<WireData> wires() { return java.util.Collections.unmodifiableCollection(wires.values()); }
    public ConnectorData connector(UUID id) { return connectors.get(id); }
    public WireData wire(UUID id) { return wires.get(id); }
    public List<ConnectorData> nearOrigin(net.minecraft.world.phys.Vec3 point) {
        var values = new java.util.ArrayList<ConnectorData>(); var center = net.minecraft.core.BlockPos.containing(point);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            for (var id : origins.getOrDefault(center.offset(x, y, z), java.util.Set.of())) values.add(connectors.get(id));
        return values;
    }
    private void origin(ConnectorData node, boolean add) {
        var key = net.minecraft.core.BlockPos.containing(node.origin());
        if (add) origins.computeIfAbsent(key, p -> new java.util.LinkedHashSet<>()).add(node.id());
        else { var ids = origins.get(key); ids.remove(node.id()); if (ids.isEmpty()) origins.remove(key); }
    }
    public Collection<ConnectorData> connectorsInChunk(long chunk) {
        return nodeChunks.getOrDefault(chunk, java.util.Set.of()).stream().map(connectors::get).toList();
    }
    public Collection<WireData> wiresInChunk(long chunk) {
        return wireChunks.getOrDefault(chunk, java.util.Set.of()).stream().map(wires::get).toList();
    }
    public java.util.Set<Long> takeChangedChunks() {
        var result = java.util.Set.copyOf(changedChunks); changedChunks.clear(); return result;
    }
    public void put(ConnectorData value) {
        var old = connectors.get(value.id());
        var attached = incident.getOrDefault(value.id(), java.util.Set.of());
        if (old != null) {
            origin(old, false);
            index(nodeChunks, value.id(), old.bounds(), false);
            for (var id : attached) { var w = wires.get(id); index(wireChunks, id, w.bounds(connector(w.first()), connector(w.second())), false); }
        }
        connectors.put(value.id(), value); index(nodeChunks, value.id(), value.bounds(), true);
        origin(value, true);
        for (var id : attached) { var w = wires.get(id); index(wireChunks, id, w.bounds(connector(w.first()), connector(w.second())), true); }
        setDirty();
    }
    public void put(WireData value) {
        if (wires.containsKey(value.id())) removeWire(value.id());
        wires.put(value.id(), value);
        incident.computeIfAbsent(value.first(), id -> new java.util.LinkedHashSet<>()).add(value.id());
        incident.computeIfAbsent(value.second(), id -> new java.util.LinkedHashSet<>()).add(value.id());
        index(wireChunks, value.id(), value.bounds(connector(value.first()), connector(value.second())), true); setDirty();
    }
    public void removeWire(UUID id) {
        var wire = wires.remove(id); if (wire == null) return;
        index(wireChunks, id, wire.bounds(connector(wire.first()), connector(wire.second())), false);
        for (var node : java.util.List.of(wire.first(), wire.second())) {
            var ids = incident.get(node); ids.remove(id); if (ids.isEmpty()) incident.remove(node);
        }
        setDirty();
    }
    public void removeConnector(UUID id) {
        for (var wire : java.util.Set.copyOf(incident.getOrDefault(id, java.util.Set.of()))) removeWire(wire);
        var old = connectors.remove(id); if (old != null) { index(nodeChunks, id, old.bounds(), false); origin(old, false); setDirty(); }
    }
    private void index(Map<Long, java.util.Set<UUID>> index, UUID id, net.minecraft.world.phys.AABB box, boolean add) {
        int minX = net.minecraft.util.Mth.floor(box.minX) >> 4, maxX = net.minecraft.util.Mth.floor(box.maxX) >> 4;
        int minZ = net.minecraft.util.Mth.floor(box.minZ) >> 4, maxZ = net.minecraft.util.Mth.floor(box.maxZ) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            long key = net.minecraft.world.level.ChunkPos.asLong(x, z); changedChunks.add(key);
            if (add) index.computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>()).add(id);
            else {
                var ids = index.get(key); if (ids != null) { ids.remove(id); if (ids.isEmpty()) index.remove(key); }
            }
        }
    }

    private static WiringSavedData load(CompoundTag tag) {
        if (tag.getInt("Version") != 1 || !tag.contains("Connectors", Tag.TAG_LIST) || !tag.contains("Wires", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing or unsupported wiring data format");
        }
        var data = new WiringSavedData();
        for (var value : tag.getList("Connectors", Tag.TAG_COMPOUND)) {
            var node = ConnectorData.load((CompoundTag)value);
            if (data.connectors.putIfAbsent(node.id(), node) != null) throw new IllegalArgumentException("Duplicate connector UUID");
        }
        var pairs = new java.util.HashSet<String>();
        for (var value : tag.getList("Wires", Tag.TAG_COMPOUND)) {
            var wire = WireData.load((CompoundTag)value);
            String pair = (wire.first().compareTo(wire.second()) < 0 ? wire.first() + "/" + wire.second()
                    : wire.second() + "/" + wire.first()) + "/" + wire.model();
            if (!data.connectors.containsKey(wire.first()) || !data.connectors.containsKey(wire.second())
                    || data.wires.putIfAbsent(wire.id(), wire) != null || !pairs.add(pair)) {
                throw new IllegalArgumentException("Invalid or duplicate wire connection");
            }
        }
        for (var node : data.connectors.values()) { data.index(data.nodeChunks, node.id(), node.bounds(), true); data.origin(node, true); }
        for (var wire : data.wires.values()) {
            data.incident.computeIfAbsent(wire.first(), id -> new java.util.LinkedHashSet<>()).add(wire.id());
            data.incident.computeIfAbsent(wire.second(), id -> new java.util.LinkedHashSet<>()).add(wire.id());
            data.index(data.wireChunks, wire.id(), wire.bounds(data.connector(wire.first()), data.connector(wire.second())), true);
        }
        data.changedChunks.clear(); return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 1);
        var nodes = new ListTag(); connectors.values().forEach(n -> nodes.add(n.save())); tag.put("Connectors", nodes);
        var edges = new ListTag(); wires.values().forEach(w -> edges.add(w.save())); tag.put("Wires", edges);
        return tag;
    }
}
