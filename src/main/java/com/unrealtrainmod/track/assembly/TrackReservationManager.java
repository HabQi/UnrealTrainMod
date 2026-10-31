/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehiclePose;
import com.unrealtrainmod.train.TrackOccupancyManager.Interval;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/** One mutex per turnout. Ownership stays an entity UUID, while checks cover its entire consist. */
public final class TrackReservationManager {
    private final ServerLevel level;
    private final TrackGraph graph;
    private final Function<UUID, List<UUID>> members;
    private final Predicate<UUID> confirmed;
    private final Function<UUID, List<Interval>> occupancy;
    private final Supplier<List<UUID>> representatives;
    private final Supplier<Set<UUID>> loadedVehicles;
    private final Set<UUID> indexed = new HashSet<>();
    private final Map<Long, Set<UUID>> chunks = new HashMap<>();
    private final Map<UUID, Set<UUID>> owned = new HashMap<>();
    private final Map<UUID, List<AABB>> corridors = new HashMap<>();

    public TrackReservationManager(ServerLevel level, TrackGraph graph, Function<UUID, List<UUID>> members,
                                   Predicate<UUID> confirmed, Function<UUID, List<Interval>> occupancy,
                                   Supplier<List<UUID>> representatives, Supplier<Set<UUID>> loadedVehicles) {
        this.level = level;
        this.graph = graph;
        this.members = members;
        this.confirmed = confirmed;
        this.occupancy = occupancy;
        this.representatives = representatives;
        this.loadedVehicles = loadedVehicles;
        for (var assembly : graph.assemblies()) indexAssembly(assembly);
    }

    /** Geometry is immutable; control-state notifications need no spatial reindexing. */
    public void indexAssembly(TrackAssemblyInstance assembly) {
        if (!indexed.add(assembly.id())) return;
        forEachChunk(assembly.bounds(), key -> chunks.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(assembly.id()));
        if (assembly.occupant() != null) {
            owned.computeIfAbsent(assembly.occupant(), ignored -> new LinkedHashSet<>()).add(assembly.id());
        }
    }

    public void removeAssembly(TrackAssemblyInstance assembly) {
        indexed.remove(assembly.id());
        forEachChunk(assembly.bounds(), key -> {
            var ids = chunks.get(key);
            ids.remove(assembly.id());
            if (ids.isEmpty()) chunks.remove(key);
        });
        corridors.remove(assembly.id());
        if (assembly.occupant() != null) removeOwner(assembly.occupant(), assembly.id());
    }

    private static void forEachChunk(AABB bounds, LongConsumer action) {
        int minX = (int)Math.floor(bounds.minX / 16), maxX = (int)Math.floor(bounds.maxX / 16);
        int minZ = (int)Math.floor(bounds.minZ / 16), maxZ = (int)Math.floor(bounds.maxZ / 16);
        // Include both boundary chunks conservatively, also for negative coordinates.
        for (long x = minX; x <= maxX; x++) {
            for (long z = minZ; z <= maxZ; z++) action.accept(ChunkPos.asLong((int)x, (int)z));
        }
    }

    private Set<UUID> candidates(List<AABB> areas, List<Interval> intervals) {
        var ids = new LinkedHashSet<UUID>();
        for (var area : areas) {
            forEachChunk(area, key -> {
                var nearby = chunks.get(key);
                if (nearby != null) ids.addAll(nearby);
            });
        }
        // Saved physical intervals must protect an assembly even without a loaded vehicle box.
        for (var interval : intervals) {
            var assembly = graph.assemblyForSegment(interval.segmentId());
            if (assembly != null) ids.add(assembly.id());
        }
        return ids;
    }

    private List<UUID> heldBy(List<UUID> owners) {
        var ids = new LinkedHashSet<UUID>();
        for (UUID owner : owners) ids.addAll(owned.getOrDefault(owner, Set.of()));
        return List.copyOf(ids);
    }

    private void removeOwner(UUID owner, UUID assemblyId) {
        var ids = owned.get(owner);
        ids.remove(assemblyId);
        if (ids.isEmpty()) owned.remove(owner);
    }

    private void setOccupant(TrackAssemblyInstance assembly, UUID owner) {
        if (Objects.equals(assembly.occupant(), owner)) return;
        if (assembly.occupant() != null) removeOwner(assembly.occupant(), assembly.id());
        if (owner != null) owned.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(assembly.id());
        // Releasing a trailing passage notifies route observers; publish ownership first.
        graph.setAssemblyOccupant(assembly.id(), owner);
    }

    public static AABB area(TrainEntity entity) {
        AABB bounds = entity.getBoundingBox().minmax(new AABB(entity.getX(), entity.getY(), entity.getZ(),
                entity.getX(), entity.getY(), entity.getZ()));
        var pose = entity.getRailPose();
        if (pose != null) {
            var rotation = VehiclePose.Body.rotation(entity.getYRot(), entity.getXRot());
            for (var bogie : List.of(pose.frontBogie(), pose.rearBogie())) {
                var p = bogie.position();
                var local = rotation.transform(new Vector3f((float)p.x(), (float)p.y(), (float)p.z()));
                double x = entity.getX() + local.x, y = entity.getY() + local.y, z = entity.getZ() + local.z;
                bounds = bounds.minmax(new AABB(x, y, z, x, y, z));
            }
        }
        return bounds.inflate(0.01);
    }

    public void refreshLoaded() {
        if (indexed.isEmpty()) return;
        var lockedAssemblies = owned.values().stream().flatMap(Set::stream).toList();
        for (UUID assemblyId : lockedAssemblies) {
            var assembly = graph.assembly(assemblyId);
            boolean complete = true, occupied = false;
            for (UUID id : members.apply(assembly.occupant())) {
                if (!(level.getEntity(id) instanceof TrainEntity entity) || entity.isRemoved()
                        || !confirmed.test(id) || VehicleCatalog.find(entity.getModelId()) == null) {
                    complete = false;
                    break;
                }
                occupied |= touches(assembly, List.of(area(entity)), occupancy.apply(id));
            }
            if (complete && !occupied) setOccupant(assembly, null);
        }
        // Persisted footprints protect a turnout even before its member entities are loaded.
        for (UUID owner : representatives.get()) acquire(members.apply(owner), List.of(), occupancy.apply(owner));
        for (UUID id : loadedVehicles.get()) {
            if (level.getEntity(id) instanceof TrainEntity train && !train.isRemoved()
                    && (train.getRailAnchor() != null || train.hasInvalidAnchor())) {
                acquire(members.apply(train.getUUID()), List.of(area(train)), occupancy.apply(train.getUUID()));
            }
        }
    }

    /** Checks every target before acquiring any lock. Call before accepting a movement sample. */
    public boolean acquire(List<UUID> owners, List<AABB> areas, List<Interval> intervals) {
        return query(areas, intervals).acquire(owners);
    }

    /** Geometry is evaluated once; ownership is read again when this movement acquires its locks. */
    public Query query(List<AABB> areas, List<Interval> intervals) {
        var touched = new ArrayList<UUID>();
        for (UUID id : candidates(areas, intervals)) {
            var assembly = graph.assembly(id);
            if (touches(assembly, areas, intervals)) touched.add(id);
        }
        return new Query(List.copyOf(touched));
    }

    /** A single movement's contacts, valid until topology or its areas/intervals change. */
    public final class Query {
        private final List<UUID> assemblies;

        private Query(List<UUID> assemblies) { this.assemblies = assemblies; }

        public boolean isEmpty() { return assemblies.isEmpty(); }

        public boolean available(List<UUID> owners) {
            for (UUID id : assemblies) {
                UUID owner = graph.assembly(id).occupant();
                if (owner != null && !owners.contains(owner)) return false;
            }
            return true;
        }

        public boolean acquire(List<UUID> owners) {
            if (!available(owners)) return false;
            for (UUID id : assemblies) {
                var assembly = graph.assembly(id);
                if (assembly.occupant() == null) setOccupant(assembly, owners.getFirst());
            }
            return true;
        }
    }

    /** Call only with a complete, validated member group. Missing members retain their locks. */
    public void releaseOutside(List<UUID> owners, List<AABB> actual, List<Interval> intervals) {
        for (UUID id : heldBy(owners)) {
            var assembly = graph.assembly(id);
            if (!touches(assembly, actual, intervals)) {
                setOccupant(assembly, null);
            }
        }
    }

    public void release(UUID vehicle) {
        for (UUID id : heldBy(List.of(vehicle))) setOccupant(graph.assembly(id), null);
    }

    public void transfer(UUID from, UUID to) {
        for (UUID id : heldBy(List.of(from))) setOccupant(graph.assembly(id), to);
    }

    public boolean touchesTurnout(List<AABB> areas, List<Interval> intervals) {
        for (UUID id : candidates(areas, intervals)) {
            if (touches(graph.assembly(id), areas, intervals)) return true;
        }
        return false;
    }

    private boolean touches(TrackAssemblyInstance assembly, List<AABB> areas, List<Interval> intervals) {
        if (intervals.stream().anyMatch(interval -> assembly.segments().contains(interval.segmentId()))) return true;
        if (areas.stream().noneMatch(area -> area.intersects(assembly.bounds()))) return false;
        var corridor = corridors.computeIfAbsent(assembly.id(), ignored -> {
            var result = new ArrayList<AABB>();
            double width = assembly.gauge() / 2 + 0.1;
            for (UUID id : assembly.segments()) {
                var geometry = graph.segment(id).geometry();
                int count = (int)Math.ceil(geometry.getLength() / 0.5);
                var previous = geometry.sample(0).position();
                for (int i = 1; i <= count; i++) {
                    var next = geometry.sample(geometry.getLength() * i / count).position();
                    result.add(new AABB(Math.min(previous.x(), next.x()) - width, Math.min(previous.y(), next.y()) - 0.25,
                            Math.min(previous.z(), next.z()) - width, Math.max(previous.x(), next.x()) + width,
                            Math.max(previous.y(), next.y()) + 3, Math.max(previous.z(), next.z()) + width));
                    previous = next;
                }
            }
            return List.copyOf(result);
        });
        return areas.stream().anyMatch(area -> corridor.stream().anyMatch(area::intersects));
    }
}
