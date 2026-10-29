/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackSegmentChange;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.path.TrackCursor;
import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import com.unrealtrainmod.train.TrainConsist.StopReason;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehiclePose;
import com.unrealtrainmod.vehicle.VehiclePoseSolver;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One authority per server level. Entity registration, commands and simulation use the server thread. */
public final class TrainManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrainManager.class);
    private static final Map<ServerLevel, TrainManager> LEVELS = new IdentityHashMap<>();
    private static final double SAMPLE_STEP = 0.25;
    private final ServerLevel level;
    private final Map<UUID, TrainConsist> trains = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Consumer<TrackSegmentChange> listener = this::trackChanged;
    private TrackGraph graph;

    private TrainManager(ServerLevel level) { this.level = level; }

    public static TrainManager get(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, TrainManager::new);
    }

    public static void register(IEventBus bus) {
        bus.addListener(TrainManager::entityJoined);
        bus.addListener(TrainManager::entityLeft);
        bus.addListener(TrainManager::levelTicked);
        bus.addListener(TrainManager::levelUnloaded);
        bus.addListener(TrainManager::serverStopped);
    }

    private static void entityJoined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof TrainEntity entity
                && (entity.getRailAnchor() != null || entity.hasInvalidAnchor())) {
            // Joining can precede FULL chunk promotion. Do not touch the graph/world here.
            get(level).pending.add(entity.getUUID());
        }
    }

    private static void entityLeft(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof TrainEntity entity) {
            var manager = LEVELS.get(level);
            if (manager != null) {
                manager.pending.remove(entity.getUUID());
                manager.trains.remove(entity.getUUID());
            }
        }
    }

    private static void levelTicked(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var manager = LEVELS.get(level);
            if (manager != null) manager.tick();
        }
    }

    private static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var manager = LEVELS.remove(level);
            if (manager != null) manager.close();
        }
    }

    private static void serverStopped(ServerStoppedEvent event) {
        for (var manager : LEVELS.values()) manager.close();
        LEVELS.clear();
    }

    private void close() {
        if (graph != null) graph.removeSegmentListener(listener);
        pending.clear();
        trains.clear();
    }

    private TrackGraph graph() {
        if (graph == null) {
            graph = TrackBlockManager.get(level).graph();
            graph.addSegmentListener(listener);
        }
        return graph;
    }

    private TrainConsist state(TrainEntity entity) {
        return trains.computeIfAbsent(entity.getUUID(), TrainConsist::new);
    }

    public TrainConsist info(TrainEntity entity) {
        var train = state(entity);
        if (train.route() == null) {
            var failure = prepare(entity, entity.getRailAnchor(), train);
            if (failure != null) stop(entity, train, failure);
        }
        return train;
    }

    /** Returns a failure reason, or null after an atomic stopped binding. */
    public StopReason bind(TrainEntity entity, TrainAnchor anchor) {
        return bind(entity, anchor, ignored -> true, false);
    }

    /** Placement validates geometry, all occupied chunks and player permissions before spawning. */
    public StopReason place(TrainEntity entity, TrainAnchor anchor, Predicate<BlockPos> allowed) {
        return bind(entity, anchor, allowed, true);
    }

    private StopReason bind(TrainEntity entity, TrainAnchor anchor, Predicate<BlockPos> allowed, boolean spawn) {
        var candidate = new TrainConsist(entity.getUUID());
        var failure = prepare(entity, anchor, candidate);
        if (failure != null) return failure;
        var solved = VehiclePoseSolver.solve(candidate.sampler(), candidate.progress(),
                VehicleCatalog.find(entity.getModelId()).motionSpec());
        if (solved.failure() != null) return poseFailure(solved.failure());
        var pose = solved.pose();
        failure = areaFailure(entity.boundsAt(pose));
        if (failure != null) return failure;
        var position = pose.body().position();
        if (!allowed.test(BlockPos.containing(position.x(), position.y(), position.z()))) return StopReason.INVALID_POSITION;
        entity.setRailAnchor(anchor);
        entity.setRailPose(pose);
        entity.setOldPosAndRot();
        if (spawn && !level.addFreshEntity(entity)) {
            pending.remove(candidate.id());
            return StopReason.INVALID_POSITION;
        }
        trains.put(candidate.id(), candidate);
        pending.remove(candidate.id());
        return null;
    }

    /** Rebuilds a stale route before starting; it never moves the vehicle during validation. */
    public StopReason start(TrainEntity entity) {
        var train = state(entity);
        var model = VehicleCatalog.find(entity.getModelId());
        if (model == null) {
            stop(entity, train, StopReason.MISSING_MODEL);
            return StopReason.MISSING_MODEL;
        }
        StopReason failure = null;
        VehiclePose pose = null;
        if (train.routeDirty()) failure = prepare(entity, entity.getRailAnchor(), train);
        if (failure == null) {
            var solved = VehiclePoseSolver.solve(train.sampler(), train.progress(), model.motionSpec());
            if (solved.failure() != null) failure = poseFailure(solved.failure());
            else {
                pose = solved.pose();
                failure = areaFailure(currentArea(entity).minmax(entity.boundsAt(pose)));
            }
        }
        if (failure != null) {
            stop(entity, train, failure);
            return failure;
        }
        entity.setRailPose(pose);
        train.start();
        return null;
    }

    public void stop(TrainEntity entity) { stop(entity, state(entity), StopReason.STOPPED); }

    private static void stop(TrainEntity entity, TrainConsist train, StopReason reason) {
        train.stop(reason);
        // Flush a final odd-tick sample too, so stopping never leaves the client one sample behind.
        entity.syncRailPose();
    }

    public StopReason setSpeed(TrainEntity entity, double speed) {
        if (!Double.isFinite(speed) || Math.abs(speed) > TrainConsist.MAX_SPEED) {
            throw new IllegalArgumentException("blocksPerSecond must be finite and within [-100, 100]");
        }
        if (speed == 0) {
            stop(entity);
            return null;
        }
        state(entity).setConfiguredSpeed(speed);
        return start(entity);
    }

    public StopReason reverse(TrainEntity entity) {
        var train = state(entity);
        train.setConfiguredSpeed(-train.configuredSpeed());
        return train.running() ? start(entity) : null;
    }

    private StopReason prepare(TrainEntity entity, TrainAnchor anchor, TrainConsist train) {
        if (anchor == null) return entity.hasInvalidAnchor() ? StopReason.INVALID_ANCHOR : StopReason.UNBOUND;
        if (VehicleCatalog.find(entity.getModelId()) == null) return StopReason.MISSING_MODEL;
        TrackGraph tracks;
        try {
            tracks = graph();
        } catch (IllegalStateException exception) {
            return StopReason.STORAGE_ERROR;
        }
        try {
            double length = tracks.segment(anchor.segmentId()).geometry().getLength();
            if (anchor.distance() > length) return StopReason.INVALID_ANCHOR;
            var seed = new TrackConnection(anchor.segmentId(), anchor.fromStart());
            var route = TrackRoute.trace(tracks, seed);
            var sampler = new TrackPathSampler(route);
            int index = route.connections().indexOf(seed);
            double progress = sampler.distance(new TrackCursor(index,
                    anchor.fromStart() ? anchor.distance() : length - anchor.distance()));
            var nodes = new HashSet<UUID>();
            for (var connection : route.connections()) {
                var segment = tracks.segment(connection.segmentId());
                nodes.add(segment.startNodeId());
                nodes.add(segment.endNodeId());
            }
            train.setRoute(route, sampler, progress, nodes);
            return null;
        } catch (IllegalArgumentException exception) {
            return StopReason.INVALID_ANCHOR;
        }
    }

    private void trackChanged(TrackSegmentChange change) {
        var segment = change.segment();
        for (var train : trains.values()) {
            if (train.usesNode(segment.startNodeId()) || train.usesNode(segment.endNodeId())) {
                train.invalidate();
                if (level.getEntity(train.id()) instanceof TrainEntity entity) entity.syncRailPose();
            }
        }
    }

    private void tick() {
        for (UUID id : Set.copyOf(pending)) {
            pending.remove(id);
            if (!trains.containsKey(id) && level.getEntity(id) instanceof TrainEntity entity) {
                var train = info(entity);
                if (train.stopReason() != StopReason.STOPPED) {
                    LOGGER.warn("Vehicle {} restored stopped: {}", id, train.stopReason());
                }
            }
        }
        if (!level.tickRateManager().runsNormally()) return;
        for (var train : List.copyOf(trains.values())) {
            if (!(level.getEntity(train.id()) instanceof TrainEntity entity) || entity.isRemoved()) {
                trains.remove(train.id());
            } else if (train.running()) {
                advance(entity, train);
            }
        }
    }

    private void advance(TrainEntity entity, TrainConsist train) {
        var model = VehicleCatalog.find(entity.getModelId());
        if (model == null) {
            stop(entity, train, StopReason.MISSING_MODEL);
            return;
        }
        AABB previous = currentArea(entity);
        var failure = areaFailure(previous);
        if (failure != null) {
            stop(entity, train, failure);
            return;
        }
        double from = train.progress();
        var range = VehiclePoseSolver.range(train.route().getLength(), model.motionSpec());
        if (!range.contains(from)) {
            stop(entity, train, StopReason.INSUFFICIENT_ROUTE);
            return;
        }
        double requested = from + train.actualSpeed() / 20;
        double target = Math.clamp(requested, range.min(), range.max());
        boolean forward = train.actualSpeed() > 0;
        double accepted = from;
        VehiclePose acceptedPose = null;
        while (accepted != target) {
            var cursor = train.sampler().locate(accepted);
            int index = cursor.segmentIndex();
            if (!forward && cursor.distance() == 0 && index > 0) index--;
            double boundary = train.sampler().distance(new TrackCursor(index,
                    forward ? train.route().segmentLength(index) : 0));
            double next = forward ? Math.min(target, Math.min(accepted + SAMPLE_STEP, boundary))
                    : Math.max(target, Math.max(accepted - SAMPLE_STEP, boundary));
            var solved = VehiclePoseSolver.solve(train.sampler(), next, model.motionSpec());
            if (solved.failure() != null) {
                failure = poseFailure(solved.failure());
                break;
            }
            var pose = solved.pose();
            var bounds = entity.boundsAt(pose);
            failure = areaFailure(previous.minmax(bounds));
            if (failure != null) break;
            accepted = next;
            previous = bounds;
            acceptedPose = pose;
        }
        if (acceptedPose != null) {
            var cursor = train.sampler().locate(accepted);
            var connection = train.route().connections().get(cursor.segmentIndex());
            double local = connection.fromStart() ? cursor.distance()
                    : train.route().segmentLength(cursor.segmentIndex()) - cursor.distance();
            entity.setRailAnchor(new TrainAnchor(connection.segmentId(), local, connection.fromStart()));
            train.setProgress(accepted);
            entity.advanceRailPose(acceptedPose);
        }
        if (failure != null) stop(entity, train, failure);
        else if (accepted == range.min() || accepted == range.max()) stop(entity, train, StopReason.END_OF_ROUTE);
    }

    private static AABB currentArea(TrainEntity entity) {
        // The authored origin may lie outside the mesh bounds, but its chunk owns the entity proxy.
        return entity.getBoundingBox().minmax(new AABB(entity.getX(), entity.getY(), entity.getZ(),
                entity.getX(), entity.getY(), entity.getZ()));
    }

    /** Only non-loading visibility/status queries; bounds include the entire authored car. */
    private StopReason areaFailure(AABB bounds) {
        var border = level.getWorldBorder();
        if (bounds.minY < level.getMinBuildHeight() || bounds.maxY > level.getMaxBuildHeight()
                || bounds.minX < border.getMinX() || bounds.maxX > border.getMaxX()
                || bounds.minZ < border.getMinZ() || bounds.maxZ > border.getMaxZ()) {
            return StopReason.INVALID_POSITION;
        }
        int minX = (int)Math.floor(bounds.minX / 16), maxX = (int)Math.floor(Math.nextDown(bounds.maxX) / 16);
        int minZ = (int)Math.floor(bounds.minZ / 16), maxZ = (int)Math.floor(Math.nextDown(bounds.maxZ) / 16);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.getChunkSource().hasChunk(x, z)
                        || !level.isPositionEntityTicking(new BlockPos(x << 4, level.getMinBuildHeight(), z << 4))) {
                    return StopReason.UNLOADED_AREA;
                }
            }
        }
        return null;
    }

    private static StopReason poseFailure(VehiclePoseSolver.Failure failure) {
        return switch (failure) {
            case INSUFFICIENT_ROUTE -> StopReason.INSUFFICIENT_ROUTE;
            case DEGENERATE_GEOMETRY -> StopReason.DEGENERATE_GEOMETRY;
        };
    }
}
