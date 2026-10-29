/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.block;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackReservationManager;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.TrackSegmentChange;
import com.unrealtrainmod.track.persistence.TrackSavedData;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** One runtime manager per server level; all operations run on the server thread. */
public final class TrackBlockManager {
    private static final Map<ServerLevel, TrackBlockManager> LEVELS = new IdentityHashMap<>();
    private final ServerLevel level;
    private final TrackGraph graph;
    private final Map<UUID, Map<BlockPos, Integer>> volumes;
    private final Map<BlockPos, Map<UUID, Integer>> owners = new HashMap<>();
    private final Map<UUID, Map<BlockPos, Integer>> prepared = new HashMap<>();
    private boolean updatingWorld;

    public static void register(IEventBus bus) {
        bus.addListener(TrackBlockManager::levelUnloaded);
        bus.addListener(TrackBlockManager::serverStopped);
    }

    public static TrackBlockManager get(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, key -> new TrackBlockManager(key, TrackSavedData.get(key)));
    }

    private static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }

    private static void serverStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }

    private TrackBlockManager(ServerLevel level, TrackSavedData data) {
        this.level = level;
        this.graph = data.graph();
        this.volumes = data.volumes();
        volumes.forEach(this::index);
        graph.setSegmentValidator(this::prepare);
        graph.addSegmentListener(this::changed);
    }

    public TrackGraph graph() {
        return graph;
    }

    /** Snapshot of the cell's owners, sorted to resolve placement ties consistently. */
    public List<TrackSegment> segmentsAt(BlockPos pos) {
        Map<UUID, Integer> indexed = owners.get(pos);
        return indexed == null ? List.of() : indexed.keySet().stream().sorted().map(graph::segment).toList();
    }

    /** True only when every indexed owner of the cell connects to this node. */
    public boolean isNodeTrackBlock(BlockPos pos, UUID nodeId) {
        Map<UUID, Integer> indexed = owners.get(pos);
        if (indexed == null) return false;
        for (UUID id : indexed.keySet()) {
            TrackSegment segment = graph.segment(id);
            if (!segment.startNodeId().equals(nodeId) && !segment.endNodeId().equals(nodeId)) return false;
        }
        return true;
    }

    private void index(UUID id, Map<BlockPos, Integer> volume) {
        volume.forEach((pos, height) -> owners.computeIfAbsent(pos, ignored -> new HashMap<>()).put(id, height));
    }

    private void prepare(List<TrackSegment> segments) {
        var batch = prepareVolumes(segments);
        prepared.clear();
        prepared.putAll(batch);
    }

    /** Performs the same checks as commit without touching staged volumes or saved/world state. */
    public void validatePlacement(List<TrackSegment> segments) {
        prepareVolumes(segments);
    }

    private Map<UUID, Map<BlockPos, Integer>> prepareVolumes(List<TrackSegment> segments) {
        var batch = new HashMap<UUID, Map<BlockPos, Integer>>();
        for (var segment : segments) batch.put(segment.id(), prepareVolume(segment, segments.size() > 1));
        if (segments.size() > 1) {
            AABB bounds = null;
            for (var volume : batch.values()) {
                for (var pos : volume.keySet()) {
                    var cell = new AABB(pos).expandTowards(0, 4, 0);
                    bounds = bounds == null ? cell : bounds.minmax(cell);
                }
            }
            for (var entity : level.getAllEntities()) {
                if (entity instanceof TrainEntity train && !train.isRemoved()
                        && (train.getRailAnchor() != null || train.hasInvalidAnchor())
                        && TrackReservationManager.area(train).intersects(bounds)) {
                    throw new TrackGraphException(TrackGraphException.Reason.VEHICLE_PRESENT);
                }
            }
        }
        return batch;
    }

    private Map<BlockPos, Integer> prepareVolume(TrackSegment segment, boolean assemblyPlacement) {
        Map<BlockPos, Integer> volume = TrackBlockVolume.create(segment.geometry());
        for (BlockPos pos : volume.keySet()) {
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                throw new TrackGraphException(TrackGraphException.Reason.INVALID_POSITION);
            }
            if (!level.hasChunkAt(pos)) throw new TrackGraphException(TrackGraphException.Reason.UNLOADED_VOLUME);
            BlockState state = level.getBlockState(pos);
            boolean shared = state.is(UnrealTrainMod.TRACK_BLOCK.get()) && owners.containsKey(pos);
            if (shared) {
                for (UUID owner : owners.get(pos).keySet()) {
                    var other = graph.segment(owner);
                    if (!assemblyPlacement && graph.assemblyForSegment(owner) == null) continue;
                    boolean endpoint = false;
                    for (UUID node : List.of(segment.startNodeId(), segment.endNodeId())) {
                        if (!node.equals(other.startNodeId()) && !node.equals(other.endNodeId())) continue;
                        var p = graph.node(node).position();
                        double radius = segment.geometry().sample(0).gauge() + 2;
                        double dx = pos.getX() + 0.5 - p.x(), dz = pos.getZ() + 0.5 - p.z();
                        if (dx * dx + dz * dz <= radius * radius) endpoint = true;
                    }
                    if (!endpoint) throw new TrackGraphException(TrackGraphException.Reason.BLOCKED_VOLUME);
                }
            }
            if (!shared && (!state.canBeReplaced() || !state.getFluidState().isEmpty())) {
                throw new TrackGraphException(TrackGraphException.Reason.BLOCKED_VOLUME);
            }
        }
        return volume;
    }

    private void changed(TrackSegmentChange change) {
        UUID id = change.segment().id();
        if (change.type() == TrackSegmentChange.Type.ADDED) {
            // The first notification installs the whole batch before any world block notifies neighbors.
            if (!prepared.containsKey(id)) return;
            var batch = new HashMap<>(prepared);
            prepared.clear();
            batch.forEach((segment, volume) -> { volumes.put(segment, volume); index(segment, volume); });
            var positions = new HashSet<BlockPos>();
            for (var volume : batch.values()) positions.addAll(volume.keySet());
            for (BlockPos pos : positions) update(pos, true);
        } else {
            Map<BlockPos, Integer> volume = volumes.remove(id);
            // Segments from saves predating world blocks have no volume.
            if (volume == null) return;
            for (BlockPos pos : volume.keySet()) {
                Map<UUID, Integer> indexed = owners.get(pos);
                indexed.remove(id);
                if (indexed.isEmpty()) owners.remove(pos);
                update(pos, false);
            }
        }
    }

    private void update(BlockPos pos, boolean placing) {
        updatingWorld = true;
        try { updateBlock(pos, placing); }
        finally { updatingWorld = false; }
    }

    private void updateBlock(BlockPos pos, boolean placing) {
        // Removal loads the affected chunks too, so a long track cannot leave unloaded remnants.
        BlockState state = level.getBlockState(pos);
        if (!placing && !state.is(UnrealTrainMod.TRACK_BLOCK.get())) return;
        Map<UUID, Integer> indexed = owners.get(pos);
        if (indexed == null) {
            level.removeBlock(pos, false);
        } else {
            int height = indexed.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
            level.setBlock(pos, UnrealTrainMod.TRACK_BLOCK.get().defaultBlockState().setValue(TrackBlock.HEIGHT, height),
                    Block.UPDATE_ALL);
        }
    }

    public void removed(BlockPos pos) {
        if (updatingWorld) return;
        Map<UUID, Integer> indexed = owners.get(pos);
        if (indexed == null) return;
        var assemblyIds = indexed.keySet().stream().map(graph::assemblyForSegment).filter(Objects::nonNull)
                .map(TrackAssemblyInstance::id).distinct().toList();
        if (!assemblyIds.isEmpty()) {
            for (UUID id : assemblyIds) graph.removeAssembly(id);
            // Preserve any ordinary external track that also owns the broken endpoint cell.
            if (owners.containsKey(pos)) update(pos, true);
            return;
        }
        // A shared cell belongs to every segment crossing it; remove each of those segments once.
        for (UUID id : List.copyOf(indexed.keySet())) graph.removeSegment(id);
    }
}
