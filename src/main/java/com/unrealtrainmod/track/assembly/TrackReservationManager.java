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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
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
    private final Map<UUID, List<AABB>> corridors = new HashMap<>();

    public TrackReservationManager(ServerLevel level, TrackGraph graph, Function<UUID, List<UUID>> members,
                                   Predicate<UUID> confirmed, Function<UUID, List<Interval>> occupancy,
                                   Supplier<List<UUID>> representatives) {
        this.level = level;
        this.graph = graph;
        this.members = members;
        this.confirmed = confirmed;
        this.occupancy = occupancy;
        this.representatives = representatives;
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
        var assemblies = graph.assemblies();
        corridors.keySet().removeIf(id -> assemblies.stream().noneMatch(assembly -> assembly.id().equals(id)));
        if (assemblies.isEmpty()) return;
        for (var assembly : assemblies) {
            if (assembly.occupant() == null) continue;
            boolean complete = true, occupied = false;
            for (UUID id : members.apply(assembly.occupant())) {
                if (!(level.getEntity(id) instanceof TrainEntity entity) || entity.isRemoved()
                        || !confirmed.test(id) || VehicleCatalog.find(entity.getModelId()) == null) {
                    complete = false;
                    break;
                }
                occupied |= touches(assembly, List.of(area(entity)), occupancy.apply(id));
            }
            if (complete && !occupied) graph.setAssemblyOccupant(assembly.id(), null);
        }
        // Persisted footprints protect a turnout even before its member entities are loaded.
        for (UUID owner : representatives.get()) acquire(members.apply(owner), List.of(), occupancy.apply(owner));
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TrainEntity train && !train.isRemoved()
                    && (train.getRailAnchor() != null || train.hasInvalidAnchor())) {
                acquire(members.apply(train.getUUID()), List.of(area(train)), occupancy.apply(train.getUUID()));
            }
        }
    }

    public boolean available(List<UUID> owners, List<AABB> areas, List<Interval> intervals) {
        for (var assembly : graph.assemblies()) {
            if (touches(assembly, areas, intervals) && assembly.occupant() != null && !owners.contains(assembly.occupant())) return false;
        }
        return true;
    }

    /** Checks every target before acquiring any lock. Call before accepting a movement sample. */
    public boolean acquire(List<UUID> owners, List<AABB> areas, List<Interval> intervals) {
        if (!available(owners, areas, intervals)) return false;
        for (var assembly : graph.assemblies()) {
            if (touches(assembly, areas, intervals) && assembly.occupant() == null) {
                graph.setAssemblyOccupant(assembly.id(), owners.getFirst());
            }
        }
        return true;
    }

    /** Call only with a complete, validated member group. Missing members retain their locks. */
    public void releaseOutside(List<UUID> owners, List<AABB> actual, List<Interval> intervals) {
        for (var assembly : graph.assemblies()) {
            if (assembly.occupant() != null && owners.contains(assembly.occupant()) && !touches(assembly, actual, intervals)) {
                graph.setAssemblyOccupant(assembly.id(), null);
            }
        }
    }

    public void release(UUID vehicle) {
        for (var assembly : graph.assemblies()) {
            if (vehicle.equals(assembly.occupant())) graph.setAssemblyOccupant(assembly.id(), null);
        }
    }

    public void transfer(UUID from, UUID to) {
        for (var assembly : graph.assemblies()) {
            if (from.equals(assembly.occupant())) graph.setAssemblyOccupant(assembly.id(), to);
        }
    }

    public boolean touchesTurnout(List<AABB> areas, List<Interval> intervals) {
        return graph.assemblies().stream().anyMatch(assembly -> touches(assembly, areas, intervals));
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
