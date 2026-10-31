/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** One file per dimension. Routes, poses and running flags are rebuilt rather than serialized. */
public final class TrainSavedData extends SavedData {
    private static final String NAME = "unrealtrainmod_trains";
    private static final int FORMAT_VERSION = 4;
    private static final Factory<TrainSavedData> FACTORY =
            new Factory<>(TrainSavedData::new, (tag, registries) -> load(tag));
    private final Map<UUID, TrainConsist> trains = new HashMap<>();

    private TrainSavedData() {}

    public static TrainSavedData get(ServerLevel level) {
        var storage = level.getDataStorage();
        var data = storage.get(FACTORY, NAME);
        if (data != null) return data;
        var file = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                .resolve("data").resolve(NAME + ".dat");
        // A failed read must not turn into an empty, writable replacement.
        if (!Files.notExists(file)) throw new IllegalStateException("Cannot read train storage: " + file);
        data = new TrainSavedData();
        storage.set(NAME, data);
        return data;
    }

    Map<UUID, TrainConsist> trains() { return Map.copyOf(trains); }

    void put(TrainConsist train) {
        trains.put(train.id(), train);
        setDirty();
    }

    void remove(UUID id) {
        if (trains.remove(id) != null) setDirty();
    }

    private static TrainSavedData load(CompoundTag tag) {
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") < 1 || tag.getInt("Version") > FORMAT_VERSION) {
            throw new IllegalArgumentException("Missing or unsupported train data version");
        }
        var data = new TrainSavedData();
        int version = tag.getInt("Version");
        var members = new HashSet<UUID>();
        var entries = list(tag, "Trains");
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            UUID id = uuid(entry, "Id");
            var cars = list(entry, "Cars");
            if (cars.isEmpty() || (version < 4 && cars.size() > 2) || (version == 1 && cars.size() != 1)) {
                throw new IllegalArgumentException("Invalid train member count");
            }
            var restored = new ArrayList<TrainCarState>(cars.size());
            for (int j = 0; j < cars.size(); j++) {
                var car = cars.getCompound(j);
                UUID entityId = uuid(car, "EntityId");
                if (!members.add(entityId)) throw new IllegalArgumentException("Duplicate train member: " + entityId);
                if (!car.contains("ModelId", Tag.TAG_STRING) || car.getString("ModelId").isBlank()) {
                    throw new IllegalArgumentException("Missing member model ID");
                }
                var modelId = ResourceLocation.parse(car.getString("ModelId"));
                double offset = number(car, "Offset");
                if (j == 0 && offset != 0) throw new IllegalArgumentException("Reference member offset must be zero");
                if (version >= 3 && !car.contains("Reversed", Tag.TAG_BYTE)) {
                    throw new IllegalArgumentException("Missing member orientation");
                }
                boolean reversed = version >= 3 && car.getBoolean("Reversed");
                if (j == 0 && reversed) throw new IllegalArgumentException("Reference member must face the route direction");
                var anchor = anchor(version == 1 ? entry : car);
                if (cars.size() > 1 && anchor == null) throw new IllegalArgumentException("Missing coupled member anchor");
                UUID previousId = car.contains("PreviousTrainId") ? uuid(car, "PreviousTrainId") : null;
                restored.add(new TrainCarState(entityId, modelId, offset, reversed, anchor, previousId));
            }
            double speed = number(entry, "ConfiguredSpeed");
            if (speed == 0 || Math.abs(speed) > TrainConsist.MAX_SPEED) {
                throw new IllegalArgumentException("Configured speed must be nonzero and within [-100, 100]");
            }
            List<TrainCoupling> couplings;
            if (version >= 4 && entry.contains("Couplings")) {
                var links = list(entry, "Couplings");
                couplings = new ArrayList<>(links.size());
                for (int j = 0; j < links.size(); j++) {
                    var link = links.getCompound(j);
                    couplings.add(new TrainCoupling(end(link, "First"), end(link, "Second")));
                }
                TrainCoupling.validate(restored, couplings);
            } else if (cars.size() == 2 && (version < 4
                    || entry.contains("LegacyTwoCar", Tag.TAG_BYTE) && entry.getBoolean("LegacyTwoCar"))) {
                couplings = null;
            } else {
                if (version >= 4) throw new IllegalArgumentException("Missing train couplings");
                couplings = List.of();
            }
            var train = new TrainConsist(id, restored, speed, couplings);
            if (data.trains.putIfAbsent(id, train) != null) throw new IllegalArgumentException("Duplicate train ID: " + id);
        }
        data.setDirty(version < FORMAT_VERSION);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", FORMAT_VERSION);
        var entries = new ListTag();
        for (var train : trains.values()) {
            var entry = new CompoundTag();
            entry.putUUID("Id", train.id());
            entry.putDouble("ConfiguredSpeed", train.configuredSpeed());
            var cars = new ListTag();
            for (var member : train.cars()) {
                var car = new CompoundTag();
                car.putUUID("EntityId", member.entityId());
                car.putString("ModelId", member.modelId().toString());
                car.putDouble("Offset", member.offset());
                car.putBoolean("Reversed", member.reversed());
                if (member.previousTrainId() != null) car.putUUID("PreviousTrainId", member.previousTrainId());
                if (member.anchor() != null) car.put("Anchor", member.anchor().save());
                cars.add(car);
            }
            entry.put("Cars", cars);
            if (train.couplingsReady()) {
                var links = new ListTag();
                for (var coupling : train.couplings()) {
                    var link = new CompoundTag();
                    putEnd(link, "First", coupling.first());
                    putEnd(link, "Second", coupling.second());
                    links.add(link);
                }
                entry.put("Couplings", links);
            } else {
                entry.putBoolean("LegacyTwoCar", true);
            }
            entries.add(entry);
        }
        tag.put("Trains", entries);
        return tag;
    }

    private static ListTag list(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing list: " + key);
        var value = (ListTag) tag.get(key);
        if (!value.isEmpty() && value.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Expected compound list: " + key);
        }
        return value;
    }

    private static UUID uuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) throw new IllegalArgumentException("Missing UUID: " + key);
        return tag.getUUID(key);
    }

    private static TrainCoupling.End end(CompoundTag tag, String prefix) {
        if (!tag.contains(prefix + "Front", Tag.TAG_BYTE)) throw new IllegalArgumentException("Missing coupler end");
        return new TrainCoupling.End(uuid(tag, prefix + "Vehicle"),
                tag.getBoolean(prefix + "Front") ? TrainCarEnd.FRONT : TrainCarEnd.REAR);
    }

    private static void putEnd(CompoundTag tag, String prefix, TrainCoupling.End end) {
        tag.putUUID(prefix + "Vehicle", end.vehicle());
        tag.putBoolean(prefix + "Front", end.end() == TrainCarEnd.FRONT);
    }

    private static TrainAnchor anchor(CompoundTag tag) {
        if (!tag.contains("Anchor")) return null;
        if (!tag.contains("Anchor", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid member anchor");
        return TrainAnchor.load(tag.getCompound("Anchor"));
    }

    private static double number(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_DOUBLE) || !Double.isFinite(tag.getDouble(key))) {
            throw new IllegalArgumentException("Expected finite double: " + key);
        }
        return tag.getDouble(key);
    }
}
