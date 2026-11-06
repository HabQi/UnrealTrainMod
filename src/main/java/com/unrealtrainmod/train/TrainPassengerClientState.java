/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side copy of synced passenger local points. It is shared by the render position lookup and
 * the walking client; the server never reads it.
 */
public final class TrainPassengerClientState {
    public record LocalPoint(double x, double y, double z) {}
    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    public static final class Entry {
        public final UUID player;
        public UUID vehicle;
        public UUID session;
        public long sequence;
        public long tick;
        public double targetX;
        public final double floorY;
        public double targetZ;
        public double prevRenderX;
        public double prevRenderZ;
        public double renderX;
        public double renderZ;
        public int idle;
        public PassengerPose pose = PassengerPose.STANDING;
        public int heightStep;

        private Entry(UUID player, UUID vehicle, UUID session, long sequence, long tick, double x, double floorY, double z,
                      PassengerPose pose, int heightStep) {
            this.player = player;
            this.vehicle = vehicle;
            this.session = session;
            this.sequence = sequence;
            this.tick = tick;
            this.targetX = x;
            this.floorY = floorY;
            this.targetZ = z;
            this.prevRenderX = this.renderX = x;
            this.prevRenderZ = this.renderZ = z;
            this.pose = pose;
            this.heightStep = heightStep;
        }
    }

    private TrainPassengerClientState() {}

    /** Updates authoritative targets and posture; render history advances only during client ticks. */
    public static Entry accept(UUID player, UUID vehicle, UUID session, long sequence, long tick,
                               double x, double floorY, double z, PassengerPose pose, int heightStep, boolean present) {
        var current = ENTRIES.get(player);
        if (!present) {
            if (current != null && current.vehicle.equals(vehicle) && current.session.equals(session)) {
                ENTRIES.remove(player);
            }
            return null;
        }
        if (current != null) {
            if (current.session.equals(session)) {
                if (sequence < current.sequence || tick < current.tick) return null;
            } else if (tick < current.tick) {
                return null;
            }
        }
        if (current == null || !current.session.equals(session)) {
            current = new Entry(player, vehicle, session, sequence, tick, x, floorY, z, pose, heightStep);
            ENTRIES.put(player, current);
            return current;
        }
        current.sequence = sequence;
        current.tick = tick;
        current.targetX = x;
        current.targetZ = z;
        current.pose = pose;
        current.heightStep = heightStep;
        current.idle = 0;
        return current;
    }

    /** Captures the previous rendered point before a client tick advances it. */
    public static void beginLocal(UUID player, UUID vehicle, UUID session) {
        var entry = ENTRIES.get(player);
        if (entry == null || !entry.vehicle.equals(vehicle) || !entry.session.equals(session)) return;
        entry.prevRenderX = entry.renderX;
        entry.prevRenderZ = entry.renderZ;
    }

    /** Commits local prediction; only an explicit large correction resets interpolation history. */
    public static void setLocal(UUID player, UUID vehicle, UUID session, double targetX, double targetZ,
                                double renderX, double renderZ, boolean snap) {
        var entry = ENTRIES.get(player);
        if (entry == null || !entry.vehicle.equals(vehicle) || !entry.session.equals(session)) return;
        entry.targetX = targetX;
        entry.targetZ = targetZ;
        if (snap) {
            entry.prevRenderX = renderX;
            entry.prevRenderZ = renderZ;
        }
        entry.renderX = renderX;
        entry.renderZ = renderZ;
        entry.idle = 0;
    }

    public static LocalPoint renderPoint(UUID player, UUID vehicle) {
        var entry = ENTRIES.get(player);
        return entry == null || !entry.vehicle.equals(vehicle) ? null : new LocalPoint(entry.renderX, entry.floorY, entry.renderZ);
    }

    /** Camera-facing local point interpolated between the previous and current client tick. */
    public static LocalPoint renderPoint(UUID player, UUID vehicle, float partialTick) {
        var entry = ENTRIES.get(player);
        return entry == null || !entry.vehicle.equals(vehicle) ? null
                : new LocalPoint(net.minecraft.util.Mth.lerp(partialTick, entry.prevRenderX, entry.renderX),
                        entry.floorY,
                        net.minecraft.util.Mth.lerp(partialTick, entry.prevRenderZ, entry.renderZ));
    }

    public static Collection<Entry> entries() { return ENTRIES.values(); }

    public static Entry entry(UUID player) { return ENTRIES.get(player); }
    public static void remove(UUID player) { ENTRIES.remove(player); }
    public static void clear() { ENTRIES.clear(); }
}
