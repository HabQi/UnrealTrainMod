/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
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
    public void put(ConnectorData value) { connectors.put(value.id(), value); setDirty(); }
    public void put(WireData value) { wires.put(value.id(), value); setDirty(); }
    public void removeWire(UUID id) { if (wires.remove(id) != null) setDirty(); }
    public void removeConnector(UUID id) {
        connectors.remove(id); wires.values().removeIf(w -> w.touches(id)); setDirty();
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
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 1);
        var nodes = new ListTag(); connectors.values().forEach(n -> nodes.add(n.save())); tag.put("Connectors", nodes);
        var edges = new ListTag(); wires.values().forEach(w -> edges.add(w.save())); tag.put("Wires", edges);
        return tag;
    }
}
