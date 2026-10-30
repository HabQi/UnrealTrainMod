/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.persistence;

import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackNode;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.TrackGeometryDefinition;
import com.unrealtrainmod.track.TrackVerticalCurveParameters;
import java.util.List;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Each ServerLevel owns one instance; derived geometry and indices are rebuilt on load. */
public final class TrackSavedData extends SavedData {
    public static final int MAX_VOLUME_BLOCKS = 16_384;
    private static final String NAME = "unrealtrainmod_tracks";
    private static final int FORMAT_VERSION = 9;
    private static final Factory<TrackSavedData> FACTORY =
            new Factory<>(TrackSavedData::new, (tag, registries) -> load(tag));
    private final TrackGraph graph = new TrackGraph(this::setDirty);
    private final Map<UUID, Map<BlockPos, Integer>> volumes = new HashMap<>();

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

    /** Mutable occupancy shared with the server-side block manager; updated with graph changes. */
    public Map<UUID, Map<BlockPos, Integer>> volumes() {
        return volumes;
    }

    public TrackGraph graph() {
        return graph;
    }

    private static TrackSavedData load(CompoundTag tag) {
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") < 1 || tag.getInt("Version") > FORMAT_VERSION) {
            throw new IllegalArgumentException("Missing or unsupported track data version");
        }
        ListTag nodes = readList(tag, "Nodes"), segments = readList(tag, "Segments");
        TrackSavedData data = new TrackSavedData();
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag node = nodes.getCompound(i);
            data.graph.addNode(readUuid(node, "Id"),
                    new TrackVector(readDouble(node, "X"), readDouble(node, "Y"), readDouble(node, "Z")),
                    readDouble(node, "Gauge"), tag.getInt("Version") >= 3 ? readCurve(node) : null,
                    tag.getInt("Version") >= 7 ? readVerticalCurve(node) : null);
        }
        for (int i = 0; i < segments.size(); i++) {
            CompoundTag segment = segments.getCompound(i);
            UUID id = readUuid(segment, "Id"), start = readUuid(segment, "StartNode"), end = readUuid(segment, "EndNode");
            if (!segment.contains("Type", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing segment type");
            String modelId = null;
            if (tag.getInt("Version") >= 2 && segment.contains("ModelId")) {
                if (!segment.contains("ModelId", Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid track model ID");
                if (segment.getString("ModelId").isBlank()) throw new IllegalArgumentException("Blank track model ID");
                modelId = ResourceLocation.parse(segment.getString("ModelId")).toString();
            }
            List<TrackPoint> controls = switch (segment.getString("Type")) {
                case "straight" -> List.of();
                case "bezier" -> List.of(readPoint(segment, "Control1"), readPoint(segment, "Control2"));
                default -> throw new IllegalArgumentException("Unknown segment type: " + segment.getString("Type"));
            };
            List<TrackPoint> vertical = tag.getInt("Version") >= 7 && segment.contains("VerticalControl1")
                    ? List.of(readPoint(segment, "VerticalControl1"), readPoint(segment, "VerticalControl2")) : List.of();
            if (segment.contains("VerticalControl1") != segment.contains("VerticalControl2")) {
                throw new IllegalArgumentException("Incomplete vertical controls");
            }
            data.graph.addDefined(id, start, end, new TrackGeometryDefinition(controls, vertical), modelId);
            if (tag.getInt("Version") >= 4 && segment.contains("Blocks")) {
                data.volumes.put(id, readVolume(segment));
            }
        }
        if (tag.getInt("Version") >= 5) {
            var assemblies = readList(tag, "Assemblies");
            for (int i = 0; i < assemblies.size(); i++) data.graph.restoreAssembly(
                    tag.getInt("Version") == 5 ? TrackAssemblyInstance.loadLegacy(assemblies.getCompound(i))
                            : TrackAssemblyInstance.load(assemblies.getCompound(i)));
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
            if (node.curve() != null) {
                CompoundTag curve = new CompoundTag();
                curve.putDouble("Angle", node.curve().angle());
                curve.putDouble("Length", node.curve().length());
                entry.put("Curve", curve);
            }
            if (node.verticalCurve() != null) {
                var curve = node.verticalCurve();
                var vertical = new CompoundTag();
                vertical.putDouble("Grade", curve.grade());
                vertical.putDouble("Length", curve.length());
                vertical.putDouble("ReferenceYaw", curve.referenceYaw());
                entry.put("VerticalCurve", vertical);
            }
            nodes.add(entry);
        }
        tag.put("Nodes", nodes);
        ListTag segments = new ListTag();
        for (TrackSegment segment : graph.segments()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", segment.id());
            entry.putUUID("StartNode", segment.startNodeId());
            entry.putUUID("EndNode", segment.endNodeId());
            if (segment.modelId() != null) entry.putString("ModelId", segment.modelId());
            Map<BlockPos, Integer> volume = volumes.get(segment.id());
            if (volume != null) writeVolume(entry, volume);
            var definition = TrackGeometryDefinition.from(segment.geometry());
            entry.putString("Type", definition.controls().isEmpty() ? "straight" : "bezier");
            if (!definition.controls().isEmpty()) {
                entry.put("Control1", writePoint(definition.controls().get(0)));
                entry.put("Control2", writePoint(definition.controls().get(1)));
            }
            if (!definition.verticalControls().isEmpty()) {
                entry.put("VerticalControl1", writePoint(definition.verticalControls().get(0)));
                entry.put("VerticalControl2", writePoint(definition.verticalControls().get(1)));
            }
            segments.add(entry);
        }
        tag.put("Segments", segments);
        var assemblies = new ListTag();
        for (var assembly : graph.assemblies()) assemblies.add(assembly.save());
        tag.put("Assemblies", assemblies);
        return tag;
    }

    private static Map<BlockPos, Integer> readVolume(CompoundTag segment) {
        if (!segment.contains("Blocks", Tag.TAG_LONG_ARRAY) || !segment.contains("BlockHeights", Tag.TAG_BYTE_ARRAY)) {
            throw new IllegalArgumentException("Invalid track volume");
        }
        long[] positions = segment.getLongArray("Blocks");
        byte[] heights = segment.getByteArray("BlockHeights");
        if (positions.length == 0 || positions.length > MAX_VOLUME_BLOCKS || positions.length != heights.length) {
            throw new IllegalArgumentException("Invalid track volume size");
        }
        Map<BlockPos, Integer> volume = new HashMap<>();
        for (int i = 0; i < positions.length; i++) {
            int height = heights[i];
            if (height < 1 || height > 16 || volume.put(BlockPos.of(positions[i]), height) != null) {
                throw new IllegalArgumentException("Invalid track volume cell");
            }
        }
        return volume;
    }

    private static void writeVolume(CompoundTag tag, Map<BlockPos, Integer> volume) {
        long[] positions = new long[volume.size()];
        byte[] heights = new byte[volume.size()];
        int index = 0;
        for (var cell : volume.entrySet()) {
            positions[index] = cell.getKey().asLong();
            heights[index] = cell.getValue().byteValue();
            index++;
        }
        tag.putLongArray("Blocks", positions);
        tag.putByteArray("BlockHeights", heights);
    }

    private static TrackCurveParameters readCurve(CompoundTag node) {
        if (!node.contains("Curve")) return null;
        if (!node.contains("Curve", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid node curve");
        CompoundTag curve = node.getCompound("Curve");
        return new TrackCurveParameters(readDouble(curve, "Angle"), readDouble(curve, "Length"));
    }

    private static TrackVerticalCurveParameters readVerticalCurve(CompoundTag node) {
        if (!node.contains("VerticalCurve")) return null;
        if (!node.contains("VerticalCurve", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid vertical curve");
        var curve = node.getCompound("VerticalCurve");
        return new TrackVerticalCurveParameters(readDouble(curve, "Grade"), readDouble(curve, "Length"),
                readDouble(curve, "ReferenceYaw"));
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
