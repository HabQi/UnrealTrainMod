/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.persistence;

import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackNode;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Each ServerLevel owns one instance; derived geometry and indices are rebuilt on load. */
public final class TrackSavedData extends SavedData {
    private static final String NAME = "unrealtrainmod_tracks";
    private static final int FORMAT_VERSION = 1;
    private static final Factory<TrackSavedData> FACTORY =
            new Factory<>(TrackSavedData::new, (tag, registries) -> load(tag));
    private final TrackGraph graph = new TrackGraph(this::setDirty);

    private TrackSavedData() {}

    public static TrackSavedData get(ServerLevel level) {
        var storage = level.getDataStorage();
        TrackSavedData data = storage.get(FACTORY, NAME);
        if (data != null) return data;
        Path file = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                .resolve("data").resolve(NAME + ".dat");
        // DimensionDataStorage returns null both for absent files and for deserialization/IO errors.
        // Only a definitely absent file may start an empty graph. Failed loads remain unwritable.
        if (!Files.notExists(file)) throw new IllegalStateException(file.toString());
        data = new TrackSavedData();
        storage.set(NAME, data);
        return data;
    }

    public TrackGraph graph() {
        return graph;
    }

    private static TrackSavedData load(CompoundTag tag) {
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != FORMAT_VERSION) {
            throw new IllegalArgumentException("Missing or unsupported track data version");
        }
        ListTag nodes = readList(tag, "Nodes"), segments = readList(tag, "Segments");
        TrackSavedData data = new TrackSavedData();
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag node = nodes.getCompound(i);
            data.graph.addNode(readUuid(node, "Id"),
                    new TrackVector(readDouble(node, "X"), readDouble(node, "Y"), readDouble(node, "Z")),
                    readDouble(node, "Gauge"));
        }
        for (int i = 0; i < segments.size(); i++) {
            CompoundTag segment = segments.getCompound(i);
            UUID id = readUuid(segment, "Id"), start = readUuid(segment, "StartNode"), end = readUuid(segment, "EndNode");
            if (!segment.contains("Type", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing segment type");
            switch (segment.getString("Type")) {
                case "straight" -> data.graph.addStraight(id, start, end);
                case "bezier" -> data.graph.addBezier(id, start, end,
                        readPoint(segment, "Control1"), readPoint(segment, "Control2"));
                default -> throw new IllegalArgumentException("Unknown segment type: " + segment.getString("Type"));
            }
        }
        // Do not publish a partly restored graph, or schedule a rewrite merely because it was loaded.
        data.setDirty(false);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", FORMAT_VERSION);
        ListTag nodes = new ListTag();
        for (TrackNode node : graph.nodes()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", node.id());
            entry.putDouble("X", node.position().x());
            entry.putDouble("Y", node.position().y());
            entry.putDouble("Z", node.position().z());
            entry.putDouble("Gauge", node.gauge());
            nodes.add(entry);
        }
        tag.put("Nodes", nodes);
        ListTag segments = new ListTag();
        for (TrackSegment segment : graph.segments()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", segment.id());
            entry.putUUID("StartNode", segment.startNodeId());
            entry.putUUID("EndNode", segment.endNodeId());
            if (segment.geometry() instanceof BezierTrackGeometry bezier) {
                entry.putString("Type", "bezier");
                entry.put("Control1", writePoint(bezier.control1()));
                entry.put("Control2", writePoint(bezier.control2()));
            } else {
                entry.putString("Type", "straight");
            }
            segments.add(entry);
        }
        tag.put("Segments", segments);
        return tag;
    }

    private static ListTag readList(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Expected a compound list: " + key);
        }
        return list;
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) throw new IllegalArgumentException("Missing or invalid UUID: " + key);
        return tag.getUUID(key);
    }

    private static double readDouble(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Expected a double: " + key);
        return tag.getDouble(key);
    }

    private static TrackPoint readPoint(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing control point: " + key);
        CompoundTag point = tag.getCompound(key);
        return new TrackPoint(readDouble(point, "X"), readDouble(point, "Z"));
    }

    private static CompoundTag writePoint(TrackPoint point) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("X", point.x());
        tag.putDouble("Z", point.z());
        return tag;
    }
}
