/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/** One mutex per turnout. Ownership stays an entity UUID, while checks cover its entire consist. */
public final class TrackReservationManager {
    private final ServerLevel level;
    private final TrackGraph graph;
    private final Function<UUID, List<UUID>> members;
    private final Predicate<UUID> confirmed;

    public TrackReservationManager(ServerLevel level, TrackGraph graph, Function<UUID, List<UUID>> members,
                                   Predicate<UUID> confirmed) {
        this.level = level;
        this.graph = graph;
        this.members = members;
        this.confirmed = confirmed;
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
                occupied |= area(entity).intersects(assembly.bounds());
            }
            if (complete && !occupied) graph.setAssemblyOccupant(assembly.id(), null);
        }
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TrainEntity train && !train.isRemoved()
                    && (train.getRailAnchor() != null || train.hasInvalidAnchor())) {
                acquire(members.apply(train.getUUID()), List.of(area(train)));
            }
        }
    }

    public boolean available(List<UUID> owners, List<AABB> areas) {
        for (var assembly : graph.assemblies()) {
            if (touches(assembly, areas) && assembly.occupant() != null && !owners.contains(assembly.occupant())) return false;
        }
        return true;
    }

    /** Checks every target before acquiring any lock. Call before accepting a movement sample. */
    public boolean acquire(List<UUID> owners, List<AABB> areas) {
        if (!available(owners, areas)) return false;
        for (var assembly : graph.assemblies()) {
            if (touches(assembly, areas) && assembly.occupant() == null) {
                graph.setAssemblyOccupant(assembly.id(), owners.getFirst());
            }
        }
        return true;
    }

    /** Call only with a complete, validated member group. Missing members retain their locks. */
    public void releaseOutside(List<UUID> owners, List<AABB> actual) {
        for (var assembly : graph.assemblies()) {
            if (assembly.occupant() != null && owners.contains(assembly.occupant()) && !touches(assembly, actual)) {
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

    public boolean touchesTurnout(List<AABB> areas) {
        return graph.assemblies().stream().anyMatch(assembly -> touches(assembly, areas));
    }

    private static boolean touches(TrackAssemblyInstance assembly, List<AABB> areas) {
        return areas.stream().anyMatch(area -> area.intersects(assembly.bounds()));
    }
}
