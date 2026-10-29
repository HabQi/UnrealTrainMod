/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;

/** Immutable world structure and stable state; occupancy is deliberately retained across unloads. */
public final class TrackAssemblyInstance {
    public enum State { STRAIGHT, DIVERGING }
    private final UUID id, a, b, c, straight, diverging, occupant;
    private final TrackAssemblyDefinition definition;
    private final TrackVector origin;
    private final double yaw, gauge;
    private final State state;
    private final long revision;
    private final TrackAssemblyDefinition.Geometry geometry;
    private final AABB bounds;

    public TrackAssemblyInstance(UUID id, TrackAssemblyDefinition definition, UUID a, UUID b, UUID c,
                                 UUID straight, UUID diverging, TrackVector origin, double yaw, double gauge,
                                 State state, long revision, UUID occupant) {
        if (!Double.isFinite(yaw) || !Double.isFinite(gauge) || gauge <= 0 || 32 * gauge / 1.435 > 256 || revision < 0
                || !Double.isFinite(origin.x()) || !Double.isFinite(origin.y()) || !Double.isFinite(origin.z())
                || new java.util.HashSet<>(List.of(a, b, c, straight, diverging)).size() != 5) {
            throw new IllegalArgumentException("Invalid turnout structure");
        }
        this.id = java.util.Objects.requireNonNull(id); this.definition = java.util.Objects.requireNonNull(definition);
        this.a = a; this.b = b; this.c = c; this.straight = straight; this.diverging = diverging;
        this.origin = origin; this.yaw = ((yaw % 360) + 540) % 360 - 180; this.gauge = gauge;
        this.state = java.util.Objects.requireNonNull(state); this.revision = revision; this.occupant = occupant;
        geometry = definition.geometry(origin, this.yaw, gauge);
        bounds = createBounds();
    }

    private TrackAssemblyInstance(TrackAssemblyInstance source, State state, long revision, UUID occupant) {
        id = source.id; definition = source.definition;
        a = source.a; b = source.b; c = source.c; straight = source.straight; diverging = source.diverging;
        origin = source.origin; yaw = source.yaw; gauge = source.gauge;
        this.state = state; this.revision = revision; this.occupant = occupant;
        geometry = source.geometry; bounds = source.bounds;
    }

    public UUID id() { return id; }
    public UUID a() { return a; }
    public UUID b() { return b; }
    public UUID c() { return c; }
    public UUID straight() { return straight; }
    public UUID diverging() { return diverging; }
    public UUID occupant() { return occupant; }
    public TrackAssemblyDefinition definition() { return definition; }
    public TrackVector origin() { return origin; }
    public double yaw() { return yaw; }
    public double gauge() { return gauge; }
    public State state() { return state; }
    public long revision() { return revision; }

    public List<UUID> ports() { return List.of(a, b, c); }
    public List<UUID> segments() { return List.of(straight, diverging); }
    public UUID activeSegment() {
        var route = definition.routes().stream().filter(r -> r.state() == state).findFirst().orElseThrow();
        return route.exit().equals("B") ? straight : diverging;
    }
    public TrackAssemblyDefinition.Geometry geometry() { return geometry; }
    public TrackAssemblyInstance withState(State value) {
        return withState(value, Math.incrementExact(revision));
    }
    public TrackAssemblyInstance withState(State value, long version) {
        if (version < 0) throw new IllegalArgumentException("Invalid turnout state revision");
        return new TrackAssemblyInstance(this, java.util.Objects.requireNonNull(value), version, occupant);
    }
    public TrackAssemblyInstance withOccupant(UUID value) {
        return new TrackAssemblyInstance(this, state, revision, value);
    }

    /** Conservative protection includes the entire three-cell-wide track volume and vehicle overhang. */
    public AABB bounds() { return bounds; }

    private AABB createBounds() {
        var g = geometry;
        double padding = Math.max(2, (gauge + 0.9) / 2);
        return new AABB(Math.min(origin.x(), Math.min(g.b().x(), g.c().x())) - padding, origin.y() - 1,
                Math.min(origin.z(), Math.min(g.b().z(), g.c().z())) - padding,
                Math.max(origin.x(), Math.max(g.b().x(), g.c().x())) + padding, origin.y() + 4,
                Math.max(origin.z(), Math.max(g.b().z(), g.c().z())) + padding);
    }

    public boolean coversChunk(int x, int z) {
        var box = bounds();
        return box.maxX >= (double)x * 16 && box.minX < (double)x * 16 + 16
                && box.maxZ >= (double)z * 16 && box.minZ < (double)z * 16 + 16;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("Id", id); tag.putString("Definition", definition.name());
        tag.putUUID("A", a); tag.putUUID("B", b); tag.putUUID("C", c);
        tag.putUUID("Straight", straight); tag.putUUID("Diverging", diverging);
        tag.putDouble("X", origin.x()); tag.putDouble("Y", origin.y()); tag.putDouble("Z", origin.z());
        tag.putDouble("Yaw", yaw); tag.putDouble("Gauge", gauge);
        tag.putString("State", state.name()); tag.putLong("Revision", revision);
        if (occupant != null) tag.putUUID("Occupant", occupant);
        return tag;
    }

    public static TrackAssemblyInstance load(CompoundTag tag) {
        for (String key : List.of("Id", "A", "B", "C", "Straight", "Diverging")) {
            if (!tag.hasUUID(key)) throw new IllegalArgumentException("Missing turnout UUID: " + key);
        }
        for (String key : List.of("X", "Y", "Z", "Yaw", "Gauge")) {
            if (!tag.contains(key, Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Invalid turnout number: " + key);
        }
        if (!tag.contains("Revision", Tag.TAG_LONG) || (tag.contains("Occupant") && !tag.hasUUID("Occupant"))) {
            throw new IllegalArgumentException("Invalid turnout state");
        }
        return new TrackAssemblyInstance(tag.getUUID("Id"), TrackAssemblyDefinition.valueOf(tag.getString("Definition")),
                tag.getUUID("A"), tag.getUUID("B"), tag.getUUID("C"), tag.getUUID("Straight"), tag.getUUID("Diverging"),
                new TrackVector(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")), tag.getDouble("Yaw"),
                tag.getDouble("Gauge"), State.valueOf(tag.getString("State")), tag.getLong("Revision"),
                tag.hasUUID("Occupant") ? tag.getUUID("Occupant") : null);
    }
}
