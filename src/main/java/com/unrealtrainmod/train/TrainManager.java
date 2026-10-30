/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackSegmentChange;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackReservationManager;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.TrainConsist.StopReason;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleMotionSpec;
import com.unrealtrainmod.vehicle.VehiclePose;
import com.unrealtrainmod.vehicle.VehiclePoseSolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One authority per level. Membership, candidate checks and commits all use the server thread. */
public final class TrainManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrainManager.class);
    private static final Map<ServerLevel, TrainManager> LEVELS = new IdentityHashMap<>();
    private final ServerLevel level;
    private final Map<UUID, TrainConsist> trains = new HashMap<>();
    private final Map<UUID, UUID> entityTrains = new HashMap<>();
    private final Set<UUID> attached = new HashSet<>();
    // Recovery belongs to the consist, independently of individual entity tick ages.
    private final Set<UUID> restored = new HashSet<>();
    private final Set<UUID> pending = new HashSet<>();
    private TrainSavedData savedData;
    private StateException storageFailure;
    private TrackGraph graph;
    private TrackReservationManager reservations;
    private final TrackOccupancyManager occupancy = new TrackOccupancyManager();
    private final Consumer<TrackSegmentChange> listener = this::trackChanged;
    private final Map<UUID, Long> assemblyRevisions = new HashMap<>();
    private final Consumer<TrackAssemblyInstance> assemblyListener = this::assemblyChanged;
    private final Consumer<TrackAssemblyInstance> assemblyRemoveListener = assembly -> assemblyRevisions.remove(assembly.id());

    private record CarCandidate(TrainCarState member, TrainEntity entity, double progress,
                                VehiclePose pose, TrainAnchor anchor, AABB bounds) {}
    private record Candidate(double progress, List<CarCandidate> cars) {
        List<AABB> areas() { return cars.stream().map(CarCandidate::bounds).toList(); }
    }

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
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof TrainEntity entity) {
            // Joining can precede FULL chunk promotion. Do not access the world or data storage here.
            get(level).pending.add(entity.getUUID());
        }
    }

    private static void entityLeft(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof TrainEntity entity) {
            var manager = LEVELS.get(level);
            if (manager != null) {
                try {
                    manager.detach(entity);
                } catch (StateException exception) {
                    LOGGER.error("Cannot detach vehicle {}: {}", entity.getUUID(), exception.reason(), exception);
                }
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
        if (graph != null) {
            graph.removeSegmentListener(listener);
            graph.removeAssemblyListener(assemblyListener);
            graph.removeAssemblyRemoveListener(assemblyRemoveListener);
        }
        pending.clear();
        attached.clear();
        restored.clear();
        entityTrains.clear();
        for (var train : trains.values()) {
            train.stop(StopReason.STOPPED);
            train.clearRoute();
        }
        trains.clear();
    }

    private TrackGraph graph() {
        if (graph == null) {
            graph = TrackBlockManager.get(level).graph();
            reservations = new TrackReservationManager(level, graph, this::reservationMembers, this::reservationMemberConfirmed,
                    id -> occupancy.intervals(entityTrains.get(id)),
                    () -> trains.values().stream().map(train -> train.cars().getFirst().entityId()).toList());
            graph.addSegmentListener(listener);
            for (var assembly : graph.assemblies()) assemblyRevisions.put(assembly.id(), assembly.revision());
            graph.addAssemblyListener(assemblyListener);
            graph.addAssemblyRemoveListener(assemblyRemoveListener);
            initializeOccupancy();
        }
        return graph;
    }

    /** Expected storage/identity failures are translated at command and item boundaries. */
    public static final class StateException extends IllegalStateException {
        private final StopReason reason;

        private StateException(StopReason reason) {
            super(reason.name());
            this.reason = reason;
        }

        private StateException(StopReason reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = reason;
        }

        public StopReason reason() { return reason; }
    }

    private TrainSavedData data() {
        if (storageFailure != null) throw storageFailure;
        if (savedData == null) {
            try {
                savedData = TrainSavedData.get(level);
            } catch (IllegalStateException exception) {
                storageFailure = new StateException(StopReason.STORAGE_ERROR, exception);
                LOGGER.error("Train storage unavailable in {}", level.dimension().location(), exception);
                throw storageFailure;
            }
            trains.putAll(savedData.trains());
            for (var train : trains.values()) {
                for (var car : train.cars()) entityTrains.put(car.entityId(), train.id());
            }
        }
        return savedData;
    }

    private void put(TrainConsist train) {
        trains.put(train.id(), train);
        for (var car : train.cars()) entityTrains.put(car.entityId(), train.id());
        savedData.put(train);
        if (graph != null && train.route() == null) seedOccupancy(train);
    }

    /** Reconstruct saved footprints without loading chunks or relocating entities. */
    private void initializeOccupancy() {
        for (var train : trains.values()) seedOccupancy(train);
    }

    private void seedOccupancy(TrainConsist train) {
        if (train.anchor() == null) return;
        var intervals = new ArrayList<TrackOccupancyManager.Interval>();
        try {
            if (train.cars().stream().anyMatch(car -> VehicleCatalog.find(car.modelId()) == null)) {
                throw new IllegalArgumentException("Missing saved vehicle model");
            }
            var snapshot = new TrainConsist(train.id(), train.cars(), train.configuredSpeed());
            snapshot.setWindow(TrainRouteWindow.initial(graph, train.anchor(), snapshot));
            for (var car : train.cars()) {
                if (!TrainMotion.matchesAt(snapshot.window(), car.anchor(), snapshot.progress() + car.offset())) {
                    throw new IllegalArgumentException("Saved member anchors disagree");
                }
            }
            intervals.addAll(TrackOccupancyManager.footprint(snapshot, snapshot.progress()));
        } catch (IllegalArgumentException exception) {
            // A damaged/closed route must not silently make the known member segments removable.
            for (var car : train.cars()) {
                var anchor = car.anchor();
                if (anchor == null) continue;
                var segment = graph.segments().stream().filter(value -> value.id().equals(anchor.segmentId())).findFirst();
                segment.ifPresent(value -> intervals.add(new TrackOccupancyManager.Interval(value.id(), 0, value.geometry().getLength())));
            }
            LOGGER.warn("Consist {} occupancy limited to saved member segments: {}", train.id(), exception.getMessage());
        }
        occupancy.update(train.id(), intervals);
    }

    public TrackOccupancyManager occupancy() {
        data();
        graph();
        return occupancy;
    }

    /** Ordinary edits are protected; only explicit administrative force bypasses this boundary. */
    public void removeTrack(UUID segmentId, boolean force) {
        data();
        var tracks = graph();
        tracks.segment(segmentId);
        var assembly = tracks.assemblyForSegment(segmentId);
        if (assembly != null) {
            removeTurnout(assembly.id(), force);
            return;
        }
        if (!force) requireUnoccupied(List.of(segmentId));
        tracks.removeSegment(segmentId);
    }

    public void removeTurnout(UUID assemblyId, boolean force) {
        data();
        var assembly = graph().assembly(assemblyId);
        if (!force) {
            requireUnoccupied(assembly.segments());
            if (graph.assembly(assemblyId).occupant() != null) {
                throw new TrackGraphException(TrackGraphException.Reason.VEHICLE_PRESENT);
            }
        }
        graph.removeAssembly(assemblyId);
    }

    private void requireUnoccupied(List<UUID> segments) {
        // A click can arrive after chunk/entity joining but before the deferred registration tick.
        for (UUID id : pending) {
            if (level.getEntity(id) instanceof TrainEntity entity && entity.getRailAnchor() != null) {
                var train = state(entity);
                if (occupancy.intervals(train.id()).isEmpty()) seedOccupancy(train);
            }
        }
        reservations.refreshLoaded();
        if (segments.stream().anyMatch(id -> !occupancy.occupants(id).isEmpty())) {
            throw new TrackGraphException(TrackGraphException.Reason.VEHICLE_PRESENT);
        }
    }

    private void remove(TrainConsist train) {
        occupancy.remove(train.id());
        trains.remove(train.id());
        restored.remove(train.id());
        for (var car : train.cars()) entityTrains.remove(car.entityId());
        savedData.remove(train.id());
    }

    private static List<UUID> memberIds(TrainConsist train) {
        return train.cars().stream().map(TrainCarState::entityId).toList();
    }

    private List<UUID> reservationMembers(UUID owner) {
        var train = trains.get(entityTrains.get(owner));
        return train == null ? List.of(owner) : memberIds(train);
    }

    private boolean reservationMemberConfirmed(UUID id) {
        var train = trains.get(entityTrains.get(id));
        if (train == null || !attached.contains(id) || !(level.getEntity(id) instanceof TrainEntity entity)) return false;
        var car = train.member(id);
        return matches(entity, train, car) && !entity.hasInvalidAnchor() && Objects.equals(car.anchor(), entity.getRailAnchor());
    }

    private TrainConsist single(TrainEntity entity, UUID id, TrainAnchor anchor, double speed) {
        var modelId = entity.getModelId();
        if (modelId == null) throw new StateException(StopReason.MISSING_MODEL);
        return new TrainConsist(id, List.of(new TrainCarState(entity.getUUID(), modelId, 0, anchor)), speed);
    }

    private static boolean matches(TrainEntity entity, TrainConsist train, TrainCarState car) {
        return !entity.hasInvalidTrainId() && (entity.getTrainId() == null || entity.getTrainId().equals(train.id()))
                && car.modelId().equals(entity.getModelId());
    }

    private TrainConsist state(TrainEntity entity) {
        data();
        var train = trains.get(entityTrains.get(entity.getUUID()));
        if (entity.hasInvalidTrainId() || (train == null && entity.getTrainId() != null)
                || (train != null && !matches(entity, train, train.member(entity.getUUID())))) {
            if (train != null) stop(train, StopReason.MEMBER_MISMATCH);
            throw new StateException(StopReason.MEMBER_MISMATCH);
        }
        if (train == null) {
            train = single(entity, UUID.randomUUID(), entity.getRailAnchor(), TrainConsist.DEFAULT_SPEED);
            put(train);
        }
        if (entity.getTrainId() == null) entity.setTrainId(train.id());
        return train;
    }

    /** Membership-changing commands must not trigger migration or relocation during validation. */
    private TrainConsist registered(TrainEntity entity) {
        data();
        var train = trains.get(entityTrains.get(entity.getUUID()));
        if (train == null || entity.getTrainId() == null || !matches(entity, train, train.member(entity.getUUID()))) {
            throw new StateException(StopReason.MEMBER_MISMATCH);
        }
        return train;
    }

    private List<TrainEntity> loadedMembers(TrainConsist train) {
        var entities = new ArrayList<TrainEntity>(train.cars().size());
        for (var car : train.cars()) {
            if (!(level.getEntity(car.entityId()) instanceof TrainEntity entity) || entity.isRemoved()
                    || !attached.contains(car.entityId())) throw new StateException(StopReason.INCOMPLETE_CONSIST);
            if (!matches(entity, train, car)) throw new StateException(StopReason.MEMBER_MISMATCH);
            if (VehicleCatalog.find(car.modelId()) == null) throw new StateException(StopReason.MISSING_MODEL);
            entities.add(entity);
        }
        return entities;
    }

    public boolean memberLoaded(UUID entityId) {
        return level.getEntity(entityId) instanceof TrainEntity entity && !entity.isRemoved();
    }

    public boolean isRestored(TrainConsist train) { return restored.contains(train.id()); }

    public TrainConsist knownTrain(UUID entityId) {
        data();
        return trains.get(entityTrains.get(entityId));
    }

    private List<TrainEntity> requireReady(TrainConsist train) {
        if (train.running()) throw new StateException(StopReason.CONSIST_NOT_STOPPED);
        var entities = loadedMembers(train);
        if (!isRestored(train) || train.routeDirty()) throw new StateException(StopReason.CONSIST_NOT_READY);
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i).hasInvalidAnchor()
                    || !Objects.equals(entities.get(i).getRailAnchor(), train.cars().get(i).anchor())) {
                throw new StateException(StopReason.CONSIST_NOT_READY);
            }
        }
        maintainWindow(train, entities);
        return entities;
    }

    private void detach(TrainEntity entity) {
        UUID entityId = entity.getUUID();
        pending.remove(entityId);
        attached.remove(entityId);
        data();
        var train = trains.get(entityTrains.get(entityId));
        var reason = entity.getRemovalReason();
        boolean destroyed = reason != null && (reason.shouldDestroy() || reason == Entity.RemovalReason.CHANGED_DIMENSION);
        if (train != null) {
            stop(train, StopReason.INCOMPLETE_CONSIST);
            train.clearRoute();
        }
        if (!destroyed) return;
        if (train == null || train.cars().size() == 1) {
            if (train != null) remove(train);
            try {
                graph();
                reservations.release(entityId);
            } catch (IllegalStateException exception) {
                LOGGER.error("Cannot release turnout occupancy for vehicle {}", entityId, exception);
            }
            return;
        }
        var survivor = train.cars().stream().filter(car -> !car.entityId().equals(entityId)).findFirst().orElseThrow().asSingle();
        remove(train);
        var remaining = new TrainConsist(train.id(), List.of(survivor), train.configuredSpeed());
        put(remaining);
        try {
            graph();
            for (var car : train.cars()) reservations.transfer(car.entityId(), survivor.entityId());
            reservations.refreshLoaded();
        } catch (IllegalStateException exception) {
            LOGGER.error("Cannot transfer turnout occupancy to vehicle {}", survivor.entityId(), exception);
        }
        sync(remaining);
    }

    public TrainConsist info(TrainEntity entity) {
        var train = state(entity);
        if (attached.add(entity.getUUID())) restore(train);
        if (isRestored(train) && !train.routeDirty()) {
            try {
                maintainWindow(train, loadedMembers(train));
            } catch (StateException exception) {
                stop(train, exception.reason());
            }
        }
        return train;
    }

    private void prepare(TrainConsist train, List<TrainEntity> entities) {
        var anchor = train.anchor();
        if (anchor == null) {
            throw new StateException(entities.getFirst().hasInvalidAnchor() ? StopReason.INVALID_ANCHOR : StopReason.UNBOUND);
        }
        for (var car : train.cars()) {
            if (VehicleCatalog.find(car.modelId()) == null) throw new StateException(StopReason.MISSING_MODEL);
        }
        TrackGraph tracks;
        try {
            tracks = graph();
        } catch (IllegalStateException exception) {
            throw new StateException(StopReason.STORAGE_ERROR, exception);
        }
        try {
            if (anchor.distance() > tracks.segment(anchor.segmentId()).geometry().getLength()) {
                throw new StateException(StopReason.INVALID_ANCHOR);
            }
            if (!TrackTransitionResolver.isSegmentEnabled(tracks, anchor.segmentId())) {
                throw new StateException(StopReason.TURNOUT_ROUTE_UNAVAILABLE);
            }
            train.setWindow(TrainRouteWindow.initial(tracks, anchor, train));
        } catch (IllegalArgumentException exception) {
            throw new StateException(StopReason.INVALID_ANCHOR, exception);
        }
    }

    /** Pure cache changes must preserve every committed checkpoint and never publish a new pose. */
    private void maintainWindow(TrainConsist train, List<TrainEntity> entities) {
        if (train.routeDirty()) throw new StateException(StopReason.TRACK_CHANGED);
        if (!TrainMotion.range(train).contains(train.progress())) throw new StateException(StopReason.INSUFFICIENT_ROUTE);
        try {
            var window = train.window().maintain(graph(), train);
            if (window == train.window()) return;
            var candidate = new TrainConsist(train.id(), train.cars(), train.configuredSpeed());
            candidate.setWindow(window);
            checkCheckpoint(candidate, calculate(candidate, entities, candidate.progress()));
            train.setWindow(window);
            occupancy.update(train.id(), TrackOccupancyManager.footprint(train, train.progress()));
        } catch (IllegalArgumentException exception) {
            throw new StateException(StopReason.TRACK_CHANGED, exception);
        }
    }

    private CarCandidate solveCar(TrainConsist train, TrainCarState car, TrainEntity entity, double progress) {
        var spec = VehicleCatalog.find(car.modelId()).motionSpec();
        var solved = VehiclePoseSolver.solve(train.sampler(), progress, spec);
        if (solved.failure() != null) throw new StateException(poseFailure(solved.failure()));
        var pose = solved.pose();
        return new CarCandidate(car, entity, progress, pose, TrainMotion.anchor(train.route(), train.sampler(), progress),
                protectedArea(entity, train, progress, spec, pose));
    }

    private Candidate calculate(TrainConsist train, List<TrainEntity> entities, double progress) {
        if (!TrainMotion.range(train).contains(progress)) throw new StateException(StopReason.INSUFFICIENT_ROUTE);
        var cars = new ArrayList<CarCandidate>(train.cars().size());
        for (int i = 0; i < train.cars().size(); i++) {
            var car = train.cars().get(i);
            var range = VehiclePoseSolver.range(train.route().getLength(), VehicleCatalog.find(car.modelId()).motionSpec());
            double local = TrainMotion.carProgress(progress, car.offset(), range);
            cars.add(solveCar(train, car, entities.get(i), local));
        }
        return new Candidate(progress, List.copyOf(cars));
    }

    private static List<AABB> currentAreas(List<TrainEntity> entities) {
        return entities.stream().map(TrackReservationManager::area).toList();
    }

    private static List<AABB> swept(List<AABB> previous, List<AABB> next) {
        var areas = new ArrayList<AABB>(previous.size());
        for (int i = 0; i < previous.size(); i++) areas.add(previous.get(i).minmax(next.get(i)));
        return areas;
    }

    private void check(TrainConsist train, List<AABB> areas, List<TrackOccupancyManager.Interval> intervals) {
        for (var area : areas) {
            var failure = areaFailure(area);
            if (failure != null) throw new StateException(failure);
        }
        for (var assembly : graph.assemblies()) {
            if (intervals.stream().anyMatch(interval -> assembly.segments().contains(interval.segmentId()))
                    && !TrackTransitionResolver.usesEnabledPath(assembly, train::usesSegment)) throw new StateException(StopReason.TURNOUT_ROUTE_UNAVAILABLE);
        }
        if (!reservations.available(memberIds(train), areas, intervals)) throw new StateException(StopReason.TURNOUT_OCCUPIED);
    }

    private void saveCandidate(TrainConsist train, Candidate candidate) {
        var members = candidate.cars().stream().map(car -> car.member().withAnchor(car.anchor())).toList();
        if (!members.equals(train.cars())) {
            train.setCars(members);
            savedData.setDirty();
        }
        train.setProgress(candidate.progress());
        occupancy.update(train.id(), TrackOccupancyManager.footprint(train, candidate.progress()));
    }

    private void commit(TrainConsist train, Candidate candidate, boolean publish) {
        saveCandidate(train, candidate);
        for (var car : candidate.cars()) {
            car.entity().setRailAnchor(car.anchor());
            car.entity().advanceRailPose(car.pose());
        }
        if (publish) sync(train);
    }

    private void sync(TrainConsist train) {
        for (var car : train.cars()) {
            if (level.getEntity(car.entityId()) instanceof TrainEntity entity && !entity.isRemoved()) entity.syncRailPose();
        }
    }

    private void stop(TrainConsist train, StopReason reason) {
        if (reason == StopReason.INCOMPLETE_CONSIST || reason == StopReason.MEMBER_MISMATCH || reason == StopReason.MISSING_MODEL) {
            restored.remove(train.id());
        }
        train.stop(reason);
        sync(train);
    }

    /** Recovery never moves a subset of the consist, and is not retried by the normal tick loop. */
    private StopReason restore(TrainConsist train) {
        restored.remove(train.id());
        train.stop(StopReason.STOPPED);
        train.clearRoute();
        try {
            var entities = loadedMembers(train);
            prepare(train, entities);
            var candidate = calculate(train, entities, train.progress());
            checkCheckpoint(train, candidate);
            var areas = new ArrayList<AABB>(entities.size());
            var relocate = new ArrayList<Boolean>(entities.size());
            for (var car : candidate.cars()) {
                boolean move = car.entity().hasInvalidAnchor()
                        || !TrainMotion.matchesAt(train.window(), car.entity().getRailAnchor(), car.progress());
                relocate.add(move);
                areas.add(move ? car.bounds() : TrackReservationManager.area(car.entity()));
            }
            reservations.refreshLoaded();
            var intervals = TrackOccupancyManager.footprint(train, candidate.progress());
            check(train, areas, intervals);
            reservations.acquire(memberIds(train), areas, intervals);
            saveCandidate(train, candidate);
            for (int i = 0; i < candidate.cars().size(); i++) {
                var car = candidate.cars().get(i);
                car.entity().setRailAnchor(car.anchor());
                if (relocate.get(i)) {
                    LOGGER.info("Restoring vehicle {} of consist {} to {}", car.member().entityId(), train.id(), car.anchor());
                    car.entity().advanceRailPose(car.pose());
                    car.entity().setOldPosAndRot();
                }
            }
            reservations.releaseOutside(memberIds(train), areas, intervals);
            restored.add(train.id());
            sync(train);
            return null;
        } catch (StateException exception) {
            stop(train, exception.reason());
            return exception.reason();
        }
    }

    private static void checkCheckpoint(TrainConsist train, Candidate candidate) {
        for (var car : candidate.cars()) {
            if (!TrainMotion.matchesAt(train.window(), car.member().anchor(), car.progress())) {
                throw new StateException(StopReason.INVALID_ANCHOR);
            }
        }
    }

    public StopReason bind(TrainEntity entity, TrainAnchor anchor) {
        return bind(entity, anchor, ignored -> true, false);
    }

    public StopReason place(TrainEntity entity, TrainAnchor anchor, Predicate<BlockPos> allowed) {
        return bind(entity, anchor, allowed, true);
    }

    private StopReason bind(TrainEntity entity, TrainAnchor anchor, Predicate<BlockPos> allowed, boolean spawn) {
        data();
        var previous = trains.get(entityTrains.get(entity.getUUID()));
        if (previous != null && previous.cars().size() > 1) return StopReason.UNCOUPLE_REQUIRED;
        var train = single(entity, previous == null ? UUID.randomUUID() : previous.id(), anchor,
                previous == null ? TrainConsist.DEFAULT_SPEED : previous.configuredSpeed());
        try {
            prepare(train, List.of(entity));
            var candidate = calculate(train, List.of(entity), train.progress());
            var car = candidate.cars().getFirst();
            reservations.refreshLoaded();
            var intervals = TrackOccupancyManager.footprint(train, candidate.progress());
            check(train, candidate.areas(), intervals);
            var p = car.pose().body().position();
            if (!allowed.test(BlockPos.containing(p.x(), p.y(), p.z()))) return StopReason.INVALID_POSITION;
            entity.setTrainId(train.id());
            entity.setRailAnchor(car.anchor());
            entity.setRailPose(car.pose());
            entity.setOldPosAndRot();
            if (spawn && !level.addFreshEntity(entity)) {
                pending.remove(entity.getUUID());
                return StopReason.INVALID_POSITION;
            }
            put(train);
            saveCandidate(train, candidate);
            attached.add(entity.getUUID());
            restored.add(train.id());
            pending.remove(entity.getUUID());
            reservations.acquire(memberIds(train), candidate.areas(), intervals);
            reservations.releaseOutside(memberIds(train), candidate.areas(), intervals);
            return null;
        } catch (StateException exception) {
            return exception.reason();
        }
    }

    public StopReason start(TrainEntity entity) {
        var train = state(entity);
        attached.add(entity.getUUID());
        if (!isRestored(train)) {
            var failure = restore(train);
            if (failure != null) return failure;
        }
        try {
            var entities = loadedMembers(train);
            if (train.routeDirty()) prepare(train, entities);
            maintainWindow(train, entities);
            var candidate = calculate(train, entities, train.progress());
            checkCheckpoint(train, candidate);
            var areas = swept(currentAreas(entities), candidate.areas());
            reservations.refreshLoaded();
            var intervals = TrackOccupancyManager.footprint(train, candidate.progress());
            check(train, areas, intervals);
            reservations.acquire(memberIds(train), areas, intervals);
            commit(train, candidate, true);
            reservations.releaseOutside(memberIds(train), candidate.areas(), intervals);
            train.start();
            return null;
        } catch (StateException exception) {
            stop(train, exception.reason());
            return exception.reason();
        }
    }

    public void stop(TrainEntity entity) {
        var train = info(entity);
        stop(train, isRestored(train) ? StopReason.STOPPED : train.stopReason());
    }

    public StopReason setSpeed(TrainEntity entity, double speed) {
        if (!Double.isFinite(speed) || Math.abs(speed) > TrainConsist.MAX_SPEED) {
            throw new IllegalArgumentException("blocksPerSecond must be finite and within [-100, 100]");
        }
        if (speed == 0) {
            stop(entity);
            return null;
        }
        var train = state(entity);
        train.setConfiguredSpeed(speed);
        savedData.setDirty();
        return start(entity);
    }

    public StopReason reverse(TrainEntity entity) {
        var train = info(entity);
        train.setConfiguredSpeed(-train.configuredSpeed());
        savedData.setDirty();
        return train.running() ? start(entity) : null;
    }

    public StopReason dismantleFailure(TrainEntity entity) {
        data();
        var train = trains.get(entityTrains.get(entity.getUUID()));
        return train != null && train.cars().size() > 1 ? StopReason.UNCOUPLE_REQUIRED : null;
    }

    public StopReason couple(TrainEntity front, TrainEntity rear) {
        if (front == rear || front.level() != level || rear.level() != level) return StopReason.COUPLING_REJECTED;
        try {
            var first = registered(front);
            var second = registered(rear);
            if (first == second || first.cars().size() != 1 || second.cars().size() != 1) return StopReason.UNCOUPLE_REQUIRED;
            requireReady(first);
            requireReady(second);
            var firstSpec = VehicleCatalog.find(front.getModelId()).motionSpec();
            var rearSpec = VehicleCatalog.find(rear.getModelId()).motionSpec();
            double gap = Math.max(firstSpec.defaultCouplerGap(), rearSpec.defaultCouplerGap());
            double offset = firstSpec.rearCouplerOffset() - rearSpec.frontCouplerOffset() - gap;
            if (!Double.isFinite(offset)) return StopReason.COUPLING_REJECTED;
            var members = List.of(first.cars().getFirst(),
                    new TrainCarState(rear.getUUID(), rear.getModelId(), offset, second.anchor()));
            var combined = new TrainConsist(first.id(), members, first.configuredSpeed());
            prepare(combined, List.of(front, rear));
            double target = TrainMotion.carProgress(combined.progress(), offset,
                    VehiclePoseSolver.range(combined.route().getLength(), rearSpec));
            double from;
            try {
                from = TrainMotion.nearestDistance(combined.window(), second.anchor(), target);
            } catch (IllegalArgumentException exception) {
                return StopReason.COUPLING_REJECTED;
            }
            var candidate = calculate(combined, List.of(front, rear), combined.progress());
            if (Math.abs(target - from) > 0.5) return StopReason.COUPLING_REJECTED;
            var a = coupler(front, firstSpec.rearCouplerOffset());
            var b = coupler(rear, rearSpec.frontCouplerOffset());
            double couplerDistance = Math.hypot(Math.hypot(a.x() - b.x(), a.z() - b.z()), a.y() - b.y());
            if (!Double.isFinite(couplerDistance) || couplerDistance > gap + 1) {
                return StopReason.COUPLING_REJECTED;
            }
            var actual = currentAreas(List.of(front, rear));
            // Include old saved poses even when the along-route adjustment is zero.
            var alignment = swept(actual, candidate.areas());
            var intervals = TrackOccupancyManager.footprint(combined, candidate.progress());
            if (reservations.touchesTurnout(alignment, intervals)) return StopReason.TURNOUT_OPERATION_BLOCKED;
            check(combined, alignment, intervals);
            var previous = actual.get(1);
            double accepted = from;
            var sampleOffsets = TrainMotion.sampleOffsets(rearSpec);
            while (accepted != target) {
                double next = TrainMotion.nextStep(combined.route(), combined.sampler(), sampleOffsets, accepted, target);
                var car = solveCar(combined, members.get(1), rear, next);
                var sweep = previous.minmax(car.bounds());
                if (reservations.touchesTurnout(List.of(sweep), intervals)) return StopReason.TURNOUT_OPERATION_BLOCKED;
                check(combined, List.of(sweep), intervals);
                previous = car.bounds();
                accepted = next;
            }
            remove(second);
            put(combined);
            saveCandidate(combined, candidate);
            front.setRailAnchor(candidate.cars().getFirst().anchor());
            rear.setTrainId(combined.id());
            rear.setRailAnchor(candidate.cars().get(1).anchor());
            rear.advanceRailPose(candidate.cars().get(1).pose());
            rear.setOldPosAndRot();
            restored.add(combined.id());
            sync(combined);
            return null;
        } catch (StateException exception) {
            return exception.reason();
        }
    }

    public StopReason uncouple(TrainEntity member) {
        try {
            var train = registered(member);
            if (train.cars().size() != 2) return StopReason.NOT_COUPLED;
            var entities = requireReady(train);
            if (reservations.touchesTurnout(currentAreas(entities), occupancy.intervals(train.id()))) return StopReason.TURNOUT_OPERATION_BLOCKED;
            var first = new TrainConsist(train.id(), List.of(train.cars().getFirst().asSingle()), train.configuredSpeed());
            var second = new TrainConsist(UUID.randomUUID(), List.of(train.cars().get(1).asSingle()), train.configuredSpeed());
            first.setWindow(train.window().copyAt(graph(), train.progress()));
            double rearProgress = TrainMotion.carProgress(train.progress(), train.cars().get(1).offset(),
                    VehiclePoseSolver.range(train.route().getLength(), VehicleCatalog.find(train.cars().get(1).modelId()).motionSpec()));
            second.setWindow(train.window().copyAt(graph(), rearProgress));
            remove(train);
            put(first);
            put(second);
            entities.get(0).setTrainId(first.id());
            entities.get(1).setTrainId(second.id());
            restored.add(first.id());
            restored.add(second.id());
            occupancy.update(first.id(), TrackOccupancyManager.footprint(first, first.progress()));
            occupancy.update(second.id(), TrackOccupancyManager.footprint(second, second.progress()));
            sync(first);
            sync(second);
            return null;
        } catch (StateException exception) {
            return exception.reason();
        }
    }

    private static TrackVector coupler(TrainEntity entity, double offset) {
        var local = VehiclePose.Body.rotation(entity.getYRot(), entity.getXRot()).transform(new Vector3f(0, 0, (float) offset));
        return new TrackVector(entity.getX() + local.x, entity.getY() + local.y, entity.getZ() + local.z);
    }

    private void trackChanged(TrackSegmentChange change) {
        var segment = change.segment();
        for (var train : trains.values()) {
            if (change.type() == TrackSegmentChange.Type.REMOVED
                    && (occupancy.occupies(train.id(), segment.id()) || (train.window() != null && train.window().occupies(segment.id(), train)))) {
                train.invalidate();
                sync(train);
            } else if (!train.routeDirty() && (train.usesNode(segment.startNodeId()) || train.usesNode(segment.endNodeId()))) {
                train.window().requestRefresh();
            }
        }
    }

    private void assemblyChanged(TrackAssemblyInstance assembly) {
        Long previous = assemblyRevisions.put(assembly.id(), assembly.revision());
        if (previous == null || previous == assembly.revision()) return;
        for (var train : trains.values()) {
            if (train.routeDirty() || assembly.ports().values().stream().noneMatch(port -> train.usesNode(port.nodeId()))) continue;
            boolean occupiedPathClosed = !train.window().occupiedPathsEnabled(graph, train);
            if (occupiedPathClosed) {
                train.invalidate();
                stop(train, StopReason.TURNOUT_ROUTE_UNAVAILABLE);
            } else train.window().requestRefresh();
        }
    }

    public void refreshTurnoutOccupancy() {
        data();
        graph();
        reservations.refreshLoaded();
    }

    private void tick() {
        if (graph != null) reservations.refreshLoaded();
        for (UUID id : Set.copyOf(pending)) {
            pending.remove(id);
            if (level.getEntity(id) instanceof TrainEntity entity) {
                try {
                    var train = info(entity);
                    if (train.stopReason() != StopReason.STOPPED && train.stopReason() != StopReason.INCOMPLETE_CONSIST) {
                        LOGGER.warn("Consist {} restored stopped: {}", train.id(), train.stopReason());
                    }
                } catch (StateException exception) {
                    LOGGER.warn("Vehicle {} could not be registered: {}", id, exception.reason());
                }
            }
        }
        if (!level.tickRateManager().runsNormally()) return;
        for (var train : List.copyOf(trains.values())) {
            if (train.running()) advance(train);
        }
    }

    private void advance(TrainConsist train) {
        Candidate accepted = null;
        StopReason failure = null;
        List<AABB> previous;
        List<TrainEntity> entities;
        double target;
        var owners = memberIds(train);
        try {
            entities = loadedMembers(train);
            maintainWindow(train, entities);
            previous = currentAreas(entities);
            check(train, previous, occupancy.intervals(train.id()));
            var range = TrainMotion.range(train);
            target = Math.clamp(train.progress() + train.actualSpeed() / 20, range.min(), range.max());
        } catch (StateException exception) {
            stop(train, exception.reason());
            return;
        }
        double progress = train.progress();
        var sampleOffsets = TrainMotion.sampleOffsets(train);
        while (progress != target) {
            try {
                double next = TrainMotion.nextStep(train.route(), train.sampler(), sampleOffsets, progress, target);
                var candidate = calculate(train, entities, next);
                var areas = swept(previous, candidate.areas());
                var intervals = TrackOccupancyManager.footprint(train, next);
                check(train, areas, intervals);
                reservations.acquire(owners, areas, intervals);
                accepted = candidate;
                progress = next;
                previous = candidate.areas();
            } catch (StateException exception) {
                failure = exception.reason();
                break;
            }
        }
        if (accepted != null) commit(train, accepted, level.getGameTime() % 2 == 0);
        reservations.releaseOutside(owners, previous, occupancy.intervals(train.id()));
        var range = TrainMotion.range(train);
        if (failure != null) stop(train, failure);
        else if ((train.actualSpeed() < 0 && progress == range.min() && train.window().startBoundary())
                || (train.actualSpeed() > 0 && progress == range.max() && train.window().endBoundary())) {
            stop(train, StopReason.END_OF_ROUTE);
        }
    }

    private static AABB protectedArea(TrainEntity entity, TrainConsist train, double progress, VehicleMotionSpec spec, VehiclePose pose) {
        AABB bounds = entity.boundsAt(pose);
        var range = VehiclePoseSolver.range(train.route().getLength(), spec);
        double front = progress == range.max() && spec.frontBogieOffset() >= 0 ? train.route().getLength() : progress + spec.frontBogieOffset();
        double rear = progress == range.min() && spec.rearBogieOffset() <= 0 ? 0 : progress + spec.rearBogieOffset();
        for (double s : new double[] {front, rear}) {
            var p = train.sampler().sample(s).position();
            bounds = bounds.minmax(new AABB(p.x(), p.y(), p.z(), p.x(), p.y(), p.z()));
        }
        return bounds.inflate(0.01);
    }

    /** Only non-loading status queries; include the complete body, bogies and entity-owning origin. */
    private StopReason areaFailure(AABB bounds) {
        var border = level.getWorldBorder();
        if (bounds.minY < level.getMinBuildHeight() || bounds.maxY > level.getMaxBuildHeight()
                || bounds.minX < border.getMinX() || bounds.maxX > border.getMaxX()
                || bounds.minZ < border.getMinZ() || bounds.maxZ > border.getMaxZ()) return StopReason.INVALID_POSITION;
        int minX = (int) Math.floor(bounds.minX / 16), maxX = (int) Math.floor(Math.nextDown(bounds.maxX) / 16);
        int minZ = (int) Math.floor(bounds.minZ / 16), maxZ = (int) Math.floor(Math.nextDown(bounds.maxZ) / 16);
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
