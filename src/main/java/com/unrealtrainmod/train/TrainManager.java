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
import com.unrealtrainmod.track.assembly.TrackTraversalContext;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.TrainConsist.StopReason;
import com.unrealtrainmod.train.map.TrainMapData;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.vehicle.VehicleMotionSpec;
import com.unrealtrainmod.vehicle.VehiclePose;
import com.unrealtrainmod.vehicle.VehiclePoseSolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
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
    private final TrainDriverManager drivers;
    private final Map<UUID, TrainConsist> trains = new HashMap<>();
    private final Map<UUID, UUID> entityTrains = new HashMap<>();
    private final Set<UUID> attached = new HashSet<>();
    // Recovery belongs to the consist, independently of individual entity tick ages.
    private final Set<UUID> restored = new HashSet<>();
    private final Set<UUID> pending = new HashSet<>();
    // Includes vehicles whose deferred registration has not run or has failed.
    private final Set<UUID> loadedVehicles = new LinkedHashSet<>();
    private TrainSavedData savedData;
    private StateException storageFailure;
    private TrackGraph graph;
    private TrackReservationManager reservations;
    private final TrackOccupancyManager occupancy = new TrackOccupancyManager();
    private final Consumer<TrackSegmentChange> listener = this::trackChanged;
    private final Map<UUID, Long> assemblyRevisions = new HashMap<>();
    private final Consumer<TrackAssemblyInstance> assemblyListener = this::assemblyChanged;
    private final Consumer<TrackAssemblyInstance> assemblyRemoveListener = assembly -> {
        reservations.removeAssembly(assembly);
        assemblyRevisions.remove(assembly.id());
    };

    private record CarCandidate(TrainCarState member, TrainEntity entity, double progress,
                                VehiclePose pose, TrainAnchor anchor, AABB bounds) {}
    private record Candidate(double progress, List<CarCandidate> cars) {
        List<AABB> areas() { return cars.stream().map(CarCandidate::bounds).toList(); }
    }
    private record MovementStep(Candidate candidate, List<AABB> actual,
                                List<TrackOccupancyManager.Interval> intervals, TrackReservationManager.Query reservations) {}
    private record CouplerPair(boolean referenceFront, boolean otherFront, double distanceSquared) {
        int direction() { return referenceFront == otherFront ? -1 : 1; }
    }

    private TrainManager(ServerLevel level) {
        this.level = level;
        this.drivers = new TrainDriverManager(level, this);
    }

    public TrainDriverManager drivers() { return drivers; }

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
            var manager = get(level);
            manager.loadedVehicles.add(entity.getUUID());
            manager.pending.add(entity.getUUID());
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
            var manager = LEVELS.get(level);
            if (manager != null) manager.close();
            LEVELS.remove(level);
        }
    }

    private static void serverStopped(ServerStoppedEvent event) {
        for (var manager : LEVELS.values()) manager.close();
        LEVELS.clear();
    }

    private void close() {
        drivers.closeAll();
        if (graph != null) {
            graph.removeSegmentListener(listener);
            graph.removeAssemblyListener(assemblyListener);
            graph.removeAssemblyRemoveListener(assemblyRemoveListener);
        }
        pending.clear();
        loadedVehicles.clear();
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
                    () -> trains.values().stream().map(train -> train.cars().getFirst().entityId()).toList(),
                    () -> loadedVehicles);
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
        List<TrackOccupancyManager.Interval> intervals;
        try {
            intervals = savedFootprint(train, graph);
        } catch (IllegalArgumentException exception) {
            // A damaged/closed route must not silently make the known member segments removable.
            intervals = anchorFootprint(train, graph);
            LOGGER.warn("Consist {} occupancy limited to saved member segments: {}", train.id(), exception.getMessage());
        }
        occupancy.update(train.id(), intervals);
    }

    private static List<TrackOccupancyManager.Interval> savedFootprint(TrainConsist train, TrackGraph tracks) {
        if (train.anchor() == null) return List.of();
        if (train.cars().stream().anyMatch(car -> VehicleCatalog.find(car.modelId()) == null)) {
            throw new IllegalArgumentException("Missing saved vehicle model");
        }
        var snapshot = new TrainConsist(train.id(), train.cars(), train.configuredSpeed());
        snapshot.setWindow(TrainRouteWindow.initial(tracks, train.anchor(), train));
        for (var car : train.cars()) {
            if (!TrainMotion.matchesAt(snapshot.window(), car, snapshot.progress() + car.offset())) {
                throw new IllegalArgumentException("Saved member anchors disagree");
            }
        }
        return TrackOccupancyManager.footprint(snapshot, snapshot.progress());
    }

    private static List<TrackOccupancyManager.Interval> anchorFootprint(TrainConsist train, TrackGraph tracks) {
        var segments = tracks.segments().stream().collect(java.util.stream.Collectors.toMap(value -> value.id(), value -> value));
        var intervals = new ArrayList<TrackOccupancyManager.Interval>();
        for (var car : train.cars()) {
            var segment = car.anchor() == null ? null : segments.get(car.anchor().segmentId());
            if (segment != null) intervals.add(new TrackOccupancyManager.Interval(segment.id(), 0, segment.geometry().getLength()));
        }
        return intervals;
    }

    /** No info(), graph(), recovery, reservations or world-loading queries belong to this path. */
    public List<TrainMapData.Entry> monitoringSnapshot(TrackGraph tracks) {
        data();
        var entries = new ArrayList<TrainMapData.Entry>();
        var displayOccupancy = new TrackOccupancyManager();
        var segments = tracks.segments().stream().collect(java.util.stream.Collectors.toMap(value -> value.id(), value -> value));
        var ordered = trains.values().stream().sorted(java.util.Comparator.comparing(TrainConsist::id)).toList();
        for (var train : ordered) {
            var cars = new ArrayList<TrainMapData.Car>();
            for (var car : train.cars()) {
                var entity = level.getEntity(car.entityId()) instanceof TrainEntity value && !value.isRemoved() ? value : null;
                var source = TrainMapData.PositionSource.MISSING_ANCHOR;
                TrackVector position = null;
                double dx = 0, dz = 0;
                var anchor = car.anchor();
                if (anchor != null) {
                    var segment = segments.get(anchor.segmentId());
                    if (segment == null) source = TrainMapData.PositionSource.MISSING_TRACK;
                    else if (anchor.distance() > segment.geometry().getLength()) source = TrainMapData.PositionSource.INVALID_ANCHOR;
                    else {
                        var pose = segment.geometry().sample(anchor.distance());
                        position = pose.position();
                        source = TrainMapData.PositionSource.SAVED;
                        double direction = (anchor.fromStart() ? 1 : -1) * car.direction() * Math.signum(train.actualSpeed());
                        dx = pose.tangent().x() * direction;
                        dz = pose.tangent().z() * direction;
                    }
                }
                if (entity != null) {
                    position = new TrackVector(entity.getX(), entity.getY(), entity.getZ());
                    source = TrainMapData.PositionSource.LIVE;
                }
                cars.add(new TrainMapData.Car(car.entityId(), car.modelId(), entity != null, source, position, dx, dz));
            }
            entries.add(new TrainMapData.Train(train.id(), train.running(), train.stopReason(), isRestored(train),
                    train.configuredSpeed(), train.actualSpeed(), cars));
            List<TrackOccupancyManager.Interval> intervals;
            if (graph != null) intervals = occupancy.intervals(train.id());
            else {
                try { intervals = savedFootprint(train, tracks); }
                catch (IllegalArgumentException exception) { intervals = anchorFootprint(train, tracks); }
            }
            displayOccupancy.update(train.id(), intervals);
            for (var interval : displayOccupancy.intervals(train.id())) entries.add(new TrainMapData.Occupancy(train.id(), null,
                    interval.segmentId(), interval.start(), interval.end()));
        }
        for (var train : ordered) {
            for (var conflict : displayOccupancy.conflicts(train.id())) {
                if (train.id().compareTo(conflict.trainId()) < 0) entries.add(new TrainMapData.Occupancy(train.id(),
                        conflict.trainId(), conflict.segmentId(), conflict.start(), conflict.end()));
            }
        }
        return List.copyOf(entries);
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
        return new TrainConsist(id, List.of(new TrainCarState(entity.getUUID(), modelId, 0, false, anchor)), speed);
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

    boolean drivingReady(TrainConsist train) {
        if (!isRestored(train) || train.routeDirty()) return false;
        try {
            loadedMembers(train);
            return true;
        } catch (StateException exception) {
            return false;
        }
    }

    public TrainConsist knownTrain(UUID entityId) {
        data();
        return trains.get(entityTrains.get(entityId));
    }

    private List<TrainEntity> requireReady(TrainConsist train) {
        if (drivers.hasDriver(train)) throw new StateException(StopReason.DRIVER_PRESENT);
        if (train.running() || train.commandControlled() && train.commandTarget() != 0) {
            throw new StateException(StopReason.CONSIST_NOT_STOPPED);
        }
        return readyMembers(train);
    }

    private List<TrainEntity> readyMembers(TrainConsist train) {
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
        loadedVehicles.remove(entityId);
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
        var survivor = train.cars().stream().filter(car -> !car.entityId().equals(entityId)).findFirst().orElseThrow();
        remove(train);
        var remaining = new TrainConsist(train.id(), List.of(survivor.asSingle()), train.configuredSpeed() * survivor.direction());
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
            if (!TrackTransitionResolver.isSegmentEnabled(tracks, anchor.segmentId(), TrainRouteWindow.context(train, false))) {
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
        return solveCar(train, car, entity, progress, VehicleCatalog.find(car.modelId()));
    }

    private CarCandidate solveCar(TrainConsist train, TrainCarState car, TrainEntity entity, double progress,
                                  VehicleDefinition model) {
        var spec = model.motionSpec();
        var solved = VehiclePoseSolver.solve(train.sampler(), progress, spec, car.direction());
        if (solved.failure() != null) throw new StateException(poseFailure(solved.failure()));
        var pose = solved.pose();
        return new CarCandidate(car, entity, progress, pose, TrainMotion.anchor(train.route(), train.sampler(), progress, car.direction()),
                protectedArea(model, solved));
    }

    private Candidate calculate(TrainConsist train, List<TrainEntity> entities, double progress) {
        return calculate(train, entities, progress, TrainMotion.prepare(train));
    }

    private Candidate calculate(TrainConsist train, List<TrainEntity> entities, double progress, TrainMotion.Prepared motion) {
        if (!motion.range().contains(progress)) throw new StateException(StopReason.INSUFFICIENT_ROUTE);
        var cars = new ArrayList<CarCandidate>(train.cars().size());
        for (int i = 0; i < train.cars().size(); i++) {
            var car = train.cars().get(i);
            var prepared = motion.cars().get(i);
            double local = TrainMotion.carProgress(progress, car.offset(), prepared.range());
            cars.add(solveCar(train, car, entities.get(i), local, prepared.model()));
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

    private MovementStep calculateStep(TrainConsist train, List<TrainEntity> entities, List<AABB> previous, double next,
                                       TrainMotion.Prepared motion, TrackTraversalContext context) {
        var candidate = calculate(train, entities, next, motion);
        var actual = candidate.areas();
        var areas = swept(previous, actual);
        var intervals = motion.footprint(next);
        var query = check(train, areas, intervals, next, context);
        return new MovementStep(candidate, actual, intervals, query);
    }

    /** Speculation stays read-only; a rejected whole-tick move falls back to the original substeps. */
    private MovementStep tryStraightStep(TrainConsist train, List<TrainEntity> entities, List<AABB> previous, double target,
                                         TrainMotion.Prepared motion, TrackTraversalContext context) {
        if (!TrainMotion.isSingleStraight(graph, motion, train.progress(), target)) return null;
        try {
            var start = calculate(train, entities, train.progress(), motion).areas();
            var step = calculateStep(train, entities, swept(previous, start), target, motion, context);
            if (!step.reservations().isEmpty()) return null;
            return step;
        } catch (StateException exception) {
            // Let the fine path accept its valid prefix and report the original stop reason.
            return null;
        }
    }

    private TrackReservationManager.Query check(TrainConsist train, List<AABB> areas, List<TrackOccupancyManager.Interval> intervals) {
        return check(train, areas, intervals, train.progress(), false);
    }

    private TrackReservationManager.Query check(TrainConsist train, List<AABB> areas, List<TrackOccupancyManager.Interval> intervals,
                                                double at, boolean candidates) {
        return check(train, areas, intervals, at, TrainRouteWindow.context(train, candidates));
    }

    private TrackReservationManager.Query check(TrainConsist train, List<AABB> areas, List<TrackOccupancyManager.Interval> intervals,
                                                double at, TrackTraversalContext context) {
        for (var area : areas) {
            var failure = areaFailure(area);
            if (failure != null) throw new StateException(failure);
        }
        if (!train.window().pathsEnabledAt(graph, train, at, context)) {
            throw new StateException(StopReason.TURNOUT_ROUTE_UNAVAILABLE);
        }
        var query = reservations.query(areas, intervals);
        if (!query.available(context.members())) throw new StateException(StopReason.TURNOUT_OCCUPIED);
        return query;
    }

    private void saveCandidate(TrainConsist train, Candidate candidate, List<TrackOccupancyManager.Interval> intervals) {
        var members = candidate.cars().stream().map(car -> car.member().withAnchor(car.anchor())).toList();
        if (!members.equals(train.cars())) {
            train.setCars(members);
            savedData.setDirty();
        }
        train.setProgress(candidate.progress());
        occupancy.update(train.id(), intervals);
    }

    private void commit(TrainConsist train, Candidate candidate, List<TrackOccupancyManager.Interval> intervals, boolean publish) {
        saveCandidate(train, candidate, intervals);
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
        boolean keepDriver = reason == StopReason.END_OF_ROUTE && drivers.hasDriver(train);
        train.stop(reason, keepDriver);
        if (keepDriver) drivers.stoppedAtEnd(train);
        else drivers.close(train);
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
                        || !TrainMotion.matchesAt(train.window(), car.member().withAnchor(car.entity().getRailAnchor()), car.progress());
                relocate.add(move);
                areas.add(move ? car.bounds() : TrackReservationManager.area(car.entity()));
            }
            reservations.refreshLoaded();
            var intervals = TrackOccupancyManager.footprint(train, candidate.progress());
            check(train, areas, intervals);
            reservations.acquire(memberIds(train), areas, intervals);
            saveCandidate(train, candidate, intervals);
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
            train.dynamics().resetBrakes(train.cars());
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
            if (!TrainMotion.matchesAt(train.window(), car.member(), car.progress())) {
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
        if (previous != null && drivers.hasDriver(previous)) return StopReason.DRIVER_PRESENT;
        if (previous != null && previous.cars().size() > 1) return StopReason.UNCOUPLE_REQUIRED;
        var train = single(entity, previous == null ? UUID.randomUUID() : previous.id(), anchor,
                previous == null ? TrainConsist.DEFAULT_SPEED : previous.configuredSpeed());
        try {
            prepare(train, List.of(entity));
            var candidate = calculate(train, List.of(entity), train.progress());
            var car = candidate.cars().getFirst();
            var areas = candidate.areas();
            reservations.refreshLoaded();
            var intervals = TrackOccupancyManager.footprint(train, candidate.progress());
            check(train, areas, intervals);
            var p = car.pose().body().position();
            if (!allowed.test(BlockPos.containing(p.x(), p.y(), p.z()))) return StopReason.INVALID_POSITION;
            entity.setTrainId(train.id());
            entity.setRailAnchor(car.anchor());
            entity.setRailPose(car.pose());
            entity.setOldPosAndRot();
            if (spawn && !level.addFreshEntity(entity)) {
                pending.remove(entity.getUUID());
                loadedVehicles.remove(entity.getUUID());
                return StopReason.INVALID_POSITION;
            }
            put(train);
            saveCandidate(train, candidate, intervals);
            attached.add(entity.getUUID());
            restored.add(train.id());
            pending.remove(entity.getUUID());
            reservations.acquire(memberIds(train), areas, intervals);
            reservations.releaseOutside(memberIds(train), areas, intervals);
            return null;
        } catch (StateException exception) {
            return exception.reason();
        }
    }

    public StopReason start(TrainEntity entity) {
        var train = state(entity);
        if (drivers.hasDriver(train)) return StopReason.DRIVER_PRESENT;
        return activate(train, entity, train.configuredSpeed());
    }

    /** Validates/refreshes the route without replacing current speed or control inputs. */
    private StopReason activate(TrainConsist train, TrainEntity entity, Double commandTarget) {
        attached.add(entity.getUUID());
        if (!isRestored(train)) {
            var failure = restore(train);
            if (failure != null) return failure;
        }
        if (commandTarget != null) train.startCommand(commandTarget);
        try {
            var entities = loadedMembers(train);
            if (train.routeDirty()) prepare(train, entities);
            maintainWindow(train, entities);
            var candidate = calculate(train, entities, train.progress());
            checkCheckpoint(train, candidate);
            var actual = candidate.areas();
            var areas = swept(currentAreas(entities), actual);
            reservations.refreshLoaded();
            var intervals = TrackOccupancyManager.footprint(train, candidate.progress());
            check(train, areas, intervals, candidate.progress(), true);
            reservations.acquire(memberIds(train), areas, intervals);
            commit(train, candidate, intervals, true);
            reservations.releaseOutside(memberIds(train), actual, intervals);
            return null;
        } catch (StateException exception) {
            stop(train, exception.reason());
            return exception.reason();
        }
    }

    TrainConsist prepareDriving(TrainEntity entity) {
        var train = state(entity);
        if (drivers.hasDriver(train)) throw new StateException(StopReason.DRIVER_PRESENT);
        var failure = activate(train, entity, null);
        if (failure != null) throw new StateException(failure);
        return train;
    }

    public void stop(TrainEntity entity) {
        var train = info(entity);
        stop(train, isRestored(train) ? StopReason.STOPPED : train.stopReason());
    }

    public StopReason setSpeed(TrainEntity entity, double speed) {
        if (!Double.isFinite(speed) || Math.abs(speed) > TrainConsist.MAX_SPEED) {
            throw new IllegalArgumentException("blocksPerSecond must be finite and within [-100, 100]");
        }
        var train = state(entity);
        if (drivers.hasDriver(train)) return StopReason.DRIVER_PRESENT;
        if (speed != 0) {
            train.setConfiguredSpeed(speed);
            savedData.setDirty();
        }
        return activate(train, entity, speed);
    }

    public StopReason reverse(TrainEntity entity) {
        var train = info(entity);
        if (drivers.hasDriver(train)) return StopReason.DRIVER_PRESENT;
        if (train.running()) return StopReason.CONSIST_NOT_STOPPED;
        stop(train, StopReason.STOPPED);
        train.setConfiguredSpeed(-train.configuredSpeed());
        train.setControl(new TrainControlState(train.configuredSpeed() > 0 ? TrainControlState.Reverser.FORWARD
                : TrainControlState.Reverser.REVERSE, 0, 1, false));
        savedData.setDirty();
        return null;
    }

    public StopReason dismantleFailure(TrainEntity entity) {
        data();
        var train = trains.get(entityTrains.get(entity.getUUID()));
        if (train != null && drivers.hasDriver(train)) return StopReason.DRIVER_PRESENT;
        return train != null && train.cars().size() > 1 ? StopReason.UNCOUPLE_REQUIRED : null;
    }

    /** A manual push validates its complete sweep before committing any position or reservation. */
    public StopReason nudge(TrainEntity entity, boolean forward) {
        try {
            var train = registered(entity);
            var entities = requireReady(train);
            var motion = TrainMotion.prepare(train);
            double target = train.progress() + (forward ? 0.5 : -0.5) * train.member(entity.getUUID()).direction();
            if (!motion.range().contains(target)) return StopReason.END_OF_ROUTE;
            var previous = currentAreas(entities);
            var areas = new ArrayList<>(previous);
            double progress = train.progress();
            var context = TrainRouteWindow.context(train, false);
            MovementStep step;
            do {
                double next = TrainMotion.nextStep(train.route(), train.sampler(), motion.sampleOffsets(), progress, target);
                step = calculateStep(train, entities, previous, next, motion, context);
                for (int i = 0; i < areas.size(); i++) areas.set(i, areas.get(i).minmax(step.actual().get(i)));
                previous = step.actual();
                progress = next;
            } while (progress != target);
            var candidate = step.candidate();
            var intervals = motion.footprint(target);
            var query = check(train, areas, intervals, target, false);
            if (!query.acquire(memberIds(train))) return StopReason.TURNOUT_OCCUPIED;
            commit(train, candidate, intervals, true);
            reservations.releaseOutside(memberIds(train), candidate.areas(), intervals);
            tryAutoCouple(entity, autoCouplingVehicles());
            return null;
        } catch (StateException exception) {
            return exception.reason();
        }
    }

    public StopReason couple(TrainEntity reference, TrainEntity other) {
        return couple(reference, other, false);
    }

    private StopReason couple(TrainEntity reference, TrainEntity other, boolean automatic) {
        if (reference == other || reference.level() != level || other.level() != level) return StopReason.COUPLING_REJECTED;
        try {
            var first = registered(reference);
            var second = registered(other);
            if (first == second || first.cars().size() != 1 || second.cars().size() != 1) return StopReason.UNCOUPLE_REQUIRED;
            if (automatic) {
                if (drivers.hasDriver(first) && drivers.hasDriver(second)) return StopReason.DRIVER_PRESENT;
                readyMembers(first);
                readyMembers(second);
            } else {
                requireReady(first);
                requireReady(second);
            }
            var firstSpec = VehicleCatalog.find(reference.getModelId()).motionSpec();
            var secondSpec = VehicleCatalog.find(other.getModelId()).motionSpec();
            double gap = Math.max(firstSpec.defaultCouplerGap(), secondSpec.defaultCouplerGap());
            var pairs = couplingPairs(reference, other, firstSpec, secondSpec);
            // Stable sorting retains rear/rear, rear/front, front/rear, front/front order for ties.
            pairs.sort(Comparator.comparingDouble(CouplerPair::distanceSquared));
            StopReason failure = StopReason.COUPLING_REJECTED;
            for (var pair : pairs) {
                if (!(pair.distanceSquared() <= (gap + 1) * (gap + 1))) continue;
                StopReason reason;
                try {
                    reason = couplePair(reference, other, first, second, firstSpec, secondSpec, pair, gap, automatic);
                } catch (StateException exception) {
                    reason = exception.reason();
                }
                if (reason == null) return null;
                if (failure == StopReason.COUPLING_REJECTED) failure = reason;
            }
            return failure;
        } catch (StateException exception) {
            return exception.reason();
        }
    }

    private StopReason couplePair(TrainEntity reference, TrainEntity other, TrainConsist first, TrainConsist second,
                                  VehicleMotionSpec firstSpec, VehicleMotionSpec secondSpec,
                                  CouplerPair pair, double gap, boolean automatic) {
        int direction = pair.direction();
        double offset = couplerOffset(firstSpec, pair.referenceFront())
                - direction * couplerOffset(secondSpec, pair.otherFront()) + (pair.referenceFront() ? gap : -gap);
        if (!Double.isFinite(offset)) return StopReason.COUPLING_REJECTED;
        var members = List.of(first.cars().getFirst(),
                new TrainCarState(other.getUUID(), other.getModelId(), offset, direction < 0, second.anchor()));
        var combined = new TrainConsist(first.id(), members, first.configuredSpeed());
        var entities = List.of(reference, other);
        prepare(combined, entities);
        double target = TrainMotion.carProgress(combined.progress(), offset,
                VehiclePoseSolver.range(combined.route().getLength(), secondSpec, direction));
        double from;
        try {
            from = TrainMotion.nearestDistance(combined.window(), second.anchor(), target, direction);
        } catch (IllegalArgumentException exception) {
            return StopReason.COUPLING_REJECTED;
        }
        if (Math.abs(target - from) > 0.5) return StopReason.COUPLING_REJECTED;
        var candidate = calculate(combined, entities, combined.progress());
        var actual = currentAreas(entities);
        // Include old saved poses even when the along-route adjustment is zero.
        var alignment = swept(actual, candidate.areas());
        var intervals = TrackOccupancyManager.footprint(combined, candidate.progress());
        if (reservations.touchesTurnout(alignment, intervals)) return StopReason.TURNOUT_OPERATION_BLOCKED;
        check(combined, alignment, intervals);
        var previous = actual.get(1);
        double accepted = from;
        var sampleOffsets = TrainMotion.sampleOffsets(secondSpec, direction);
        while (accepted != target) {
            double next = TrainMotion.nextStep(combined.route(), combined.sampler(), sampleOffsets, accepted, target);
            var car = solveCar(combined, members.get(1), other, next);
            var sweep = previous.minmax(car.bounds());
            if (reservations.touchesTurnout(List.of(sweep), intervals)) return StopReason.TURNOUT_OPERATION_BLOCKED;
            check(combined, List.of(sweep), intervals);
            previous = car.bounds();
            accepted = next;
        }
        remove(second);
        put(combined);
        saveCandidate(combined, candidate, intervals);
        reference.setRailAnchor(candidate.cars().getFirst().anchor());
        other.setTrainId(combined.id());
        other.setRailAnchor(candidate.cars().get(1).anchor());
        other.advanceRailPose(candidate.cars().get(1).pose());
        other.setOldPosAndRot();
        restored.add(combined.id());
        reference.setCouplingEnabled(false);
        other.setCouplingEnabled(false);
        if (automatic) drivers.coupled(first, second, combined);
        sync(combined);
        return null;
    }

    public StopReason uncouple(TrainEntity member) {
        try {
            var train = registered(member);
            if (drivers.hasDriver(train)) return StopReason.DRIVER_PRESENT;
            if (train.cars().size() != 2) return StopReason.NOT_COUPLED;
            var entities = requireReady(train);
            if (reservations.touchesTurnout(currentAreas(entities), occupancy.intervals(train.id()))) return StopReason.TURNOUT_OPERATION_BLOCKED;
            var first = new TrainConsist(train.id(), List.of(train.cars().getFirst().asSingle()), train.configuredSpeed());
            var detached = train.cars().get(1);
            var second = new TrainConsist(UUID.randomUUID(), List.of(detached.asSingle()), train.configuredSpeed() * detached.direction());
            first.setWindow(train.window().copyAt(graph(), train.progress()));
            var detachedSpec = VehicleCatalog.find(detached.modelId()).motionSpec();
            double otherProgress = TrainMotion.carProgress(train.progress(), detached.offset(),
                    VehiclePoseSolver.range(train.route().getLength(), detachedSpec, detached.direction()));
            second.setWindow(detached.reversed() ? train.window().reversedAt(graph(), otherProgress, detachedSpec)
                    : train.window().copyAt(graph(), otherProgress));
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

    private static double couplerOffset(VehicleMotionSpec spec, boolean front) {
        return front ? spec.frontCouplerOffset() : spec.rearCouplerOffset();
    }

    private static ArrayList<CouplerPair> couplingPairs(TrainEntity reference, TrainEntity other,
                                                      VehicleMotionSpec firstSpec, VehicleMotionSpec secondSpec) {
        var first = new TrackVector[]{coupler(reference, firstSpec.rearCouplerOffset()), coupler(reference, firstSpec.frontCouplerOffset())};
        var second = new TrackVector[]{coupler(other, secondSpec.rearCouplerOffset()), coupler(other, secondSpec.frontCouplerOffset())};
        var pairs = new ArrayList<CouplerPair>(4);
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                double x = first[a].x() - second[b].x(), y = first[a].y() - second[b].y(), z = first[a].z() - second[b].z();
                pairs.add(new CouplerPair(a == 1, b == 1, x * x + y * y + z * z));
            }
        }
        return pairs;
    }

    private List<TrainEntity> autoCouplingVehicles() {
        var vehicles = new ArrayList<TrainEntity>();
        boolean enabled = false;
        for (var id : loadedVehicles) {
            var train = trains.get(entityTrains.get(id));
            if (train == null || train.cars().size() != 1 || !isRestored(train) || train.routeDirty()) continue;
            if (level.getEntity(id) instanceof TrainEntity entity && !entity.isRemoved()) {
                vehicles.add(entity);
                enabled |= entity.isCouplingEnabled();
            }
        }
        return enabled && vehicles.size() > 1 ? vehicles : List.of();
    }

    private static boolean couplersNear(TrainEntity front, TrainEntity rear, double extra) {
        var firstSpec = VehicleCatalog.find(front.getModelId()).motionSpec();
        var secondSpec = VehicleCatalog.find(rear.getModelId()).motionSpec();
        double radius = Math.max(firstSpec.defaultCouplerGap(), secondSpec.defaultCouplerGap()) + 1 + extra;
        return couplingPairs(front, rear, firstSpec, secondSpec).stream().anyMatch(pair -> pair.distanceSquared() <= radius * radius);
    }

    private boolean tryAutoCouple(TrainEntity entity, List<TrainEntity> vehicles) {
        var train = trains.get(entityTrains.get(entity.getUUID()));
        if (train == null || train.cars().size() != 1) return false;
        for (var other : vehicles) {
            if (entity == other || !entity.isCouplingEnabled() && !other.isCouplingEnabled()) continue;
            var peer = trains.get(entityTrains.get(other.getUUID()));
            if (peer == null || peer.cars().size() != 1) continue;
            if (couplersNear(entity, other, 0) && couple(entity, other, true) == null) return true;
        }
        return false;
    }

    private void trackChanged(TrackSegmentChange change) {
        var segment = change.segment();
        for (var train : trains.values()) {
            if (change.type() == TrackSegmentChange.Type.REMOVED
                    && (occupancy.occupies(train.id(), segment.id()) || (train.window() != null && train.window().occupies(segment.id(), train)))) {
                train.invalidate();
                stop(train, StopReason.TRACK_CHANGED);
            } else if (!train.routeDirty() && (train.usesNode(segment.startNodeId()) || train.usesNode(segment.endNodeId()))) {
                train.window().requestRefresh();
            }
        }
    }

    private void assemblyChanged(TrackAssemblyInstance assembly) {
        reservations.indexAssembly(assembly);
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
        drivers.tick();
        var couplingVehicles = autoCouplingVehicles();
        for (var entity : couplingVehicles) tryAutoCouple(entity, couplingVehicles);
        for (var train : List.copyOf(trains.values())) {
            if (trains.get(train.id()) == train && train.simulationActive()) advance(train, couplingVehicles);
        }
        drivers.publish();
    }

    private void advance(TrainConsist train, List<TrainEntity> couplingVehicles) {
        boolean moved = false;
        var owners = memberIds(train);
        try {
            var entities = loadedMembers(train);
            var vehicle = entities.getFirst();
            var couplingPeers = train.cars().size() == 1 ? couplingVehicles.stream()
                    .filter(other -> other != vehicle && (vehicle.isCouplingEnabled() || other.isCouplingEnabled()))
                    .filter(other -> couplersNear(vehicle, other, TrainConsist.MAX_SPEED / 20)).toList() : List.<TrainEntity>of();
            var previous = currentAreas(entities);
            maintainWindow(train, entities);
            var motion = TrainMotion.prepare(train);
            double oldSpeed = train.actualSpeed();
            double remaining = TrainDynamics.advance(train);
            double nextSpeed = train.actualSpeed();
            double acceleration = train.dynamics().acceleration(), grade = train.dynamics().grade();
            // The last braking tick still travels in the old direction before reaching zero.
            if (nextSpeed == 0 && oldSpeed != 0) train.dynamics().update(oldSpeed, acceleration, grade);
            double movementDirection = Math.signum(remaining);
            var context = new TrackTraversalContext(owners, true, train.planningDirection() > 0);
            boolean tryStraight = couplingPeers.isEmpty();
            while (remaining != 0 && train.simulationActive()) {
                maintainWindow(train, entities);
                if (motion.window() != train.window()) motion = TrainMotion.prepare(train);
                double progress = train.progress();
                check(train, previous, occupancy.intervals(train.id()), progress, context);
                var range = motion.range();
                double requested = progress + remaining;
                double target = Math.clamp(requested, range.min(), range.max());
                if (target == progress) break;
                MovementStep step = null;
                if (tryStraight) {
                    tryStraight = false;
                    step = tryStraightStep(train, entities, previous, target, motion, context);
                }
                List<TrainTurnoutMovement.Action> actions = List.of();
                if (step == null) {
                    double next = TrainMotion.nextStep(train.route(), train.sampler(), motion.sampleOffsets(), progress, target);
                    var turnoutMovement = train.window().turnoutMovement(graph);
                    next = turnoutMovement.nextBoundary(motion, progress, next);
                    step = calculateStep(train, entities, previous, next, motion, context);
                    try {
                        actions = turnoutMovement.propose(motion, context, progress, next);
                        for (var action : actions) graph.validateTrailingPass(action.assemblyId(), owners, action.pathId());
                    } catch (IllegalArgumentException exception) {
                        throw new StateException(StopReason.TURNOUT_ROUTE_UNAVAILABLE, exception);
                    }
                }
                double next = step.candidate().progress();
                if (!step.reservations().acquire(owners)) throw new StateException(StopReason.TURNOUT_OCCUPIED);
                for (var action : actions) if (action.begin()) graph.beginTrailingPass(action.assemblyId(), owners, action.pathId());
                for (var action : actions) if (action.throwSwitch()) graph.throwTrailingPass(action.assemblyId(), owners);
                // Commit each accepted substep before release can restore a turnout and notify route observers.
                commit(train, step.candidate(), step.intervals(), false);
                moved = true;
                previous = step.actual();
                remaining = next == requested ? 0 : remaining - (next - progress);
                reservations.releaseOutside(owners, previous, step.intervals());
                if (tryAutoCouple(vehicle, couplingPeers)) return;
            }
            if (train.simulationActive()) train.dynamics().update(nextSpeed, acceleration, grade);
            if (moved && level.getGameTime() % 2 == 0) sync(train);
            var range = motion.range();
            if ((movementDirection < 0 && train.progress() == range.min() && train.window().startBoundary())
                    || (movementDirection > 0 && train.progress() == range.max() && train.window().endBoundary())) {
                stop(train, StopReason.END_OF_ROUTE);
            }
        } catch (StateException exception) {
            stop(train, exception.reason());
        }
    }

    private static AABB protectedArea(VehicleDefinition model, VehiclePoseSolver.Result solved) {
        AABB bounds = solved.pose().bounds(model);
        for (var p : List.of(solved.frontTrackPosition(), solved.rearTrackPosition())) {
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
