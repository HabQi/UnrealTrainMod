/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.block;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.TrackSegmentChange;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Owned by the dimension's SavedData; all operations run on the server thread. */
public final class TrackBlockManager {
    private final ServerLevel level;
    private final TrackGraph graph;
    private final Map<UUID, Map<BlockPos, Integer>> volumes;
    private final Map<BlockPos, Map<UUID, Integer>> owners = new HashMap<>();
    private Map<BlockPos, Integer> prepared;

    public TrackBlockManager(ServerLevel level, TrackGraph graph, Map<UUID, Map<BlockPos, Integer>> volumes) {
        this.level = level;
        this.graph = graph;
        this.volumes = volumes;
        volumes.forEach(this::index);
        graph.setSegmentValidator(this::prepare);
        graph.addSegmentListener(this::changed);
    }

    private void index(UUID id, Map<BlockPos, Integer> volume) {
        volume.forEach((pos, height) -> owners.computeIfAbsent(pos, ignored -> new HashMap<>()).put(id, height));
    }

    private void prepare(TrackSegment segment) {
        Map<BlockPos, Integer> volume = TrackBlockVolume.create(segment.geometry());
        for (BlockPos pos : volume.keySet()) {
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                throw new TrackGraphException(TrackGraphException.Reason.INVALID_POSITION);
            }
            if (!level.hasChunkAt(pos)) throw new TrackGraphException(TrackGraphException.Reason.UNLOADED_VOLUME);
            BlockState state = level.getBlockState(pos);
            boolean shared = state.is(UnrealTrainMod.TRACK_BLOCK.get()) && owners.containsKey(pos);
            if (!shared && (!state.canBeReplaced() || !state.getFluidState().isEmpty())) {
                throw new TrackGraphException(TrackGraphException.Reason.BLOCKED_VOLUME);
            }
        }
        prepared = volume;
    }

    private void changed(TrackSegmentChange change) {
        UUID id = change.segment().id();
        if (change.type() == TrackSegmentChange.Type.ADDED) {
            Map<BlockPos, Integer> volume = prepared;
            prepared = null;
            volumes.put(id, volume);
            index(id, volume);
            for (BlockPos pos : volume.keySet()) update(pos, true);
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
        Map<UUID, Integer> indexed = owners.get(pos);
        if (indexed == null) return;
        // A shared cell belongs to every segment crossing it; remove each of those segments once.
        for (UUID id : List.copyOf(indexed.keySet())) graph.removeSegment(id);
    }
}
