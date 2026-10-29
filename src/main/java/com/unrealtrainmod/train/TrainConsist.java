/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import java.util.Set;
import java.util.UUID;

/** Server-owned runtime state. This first version contains exactly one car, identified by its entity UUID. */
public final class TrainConsist {
    public enum StopReason {
        STOPPED, END_OF_ROUTE, UNLOADED_AREA, INVALID_POSITION, TRACK_CHANGED,
        INVALID_ANCHOR, MISSING_MODEL, STORAGE_ERROR, UNBOUND,
        INSUFFICIENT_ROUTE, DEGENERATE_GEOMETRY
    }

    public static final double DEFAULT_SPEED = 4;
    public static final double MAX_SPEED = 100;
    private final UUID id;
    private TrackRoute route;
    private TrackPathSampler sampler;
    private Set<UUID> routeNodes = Set.of();
    private double progress;
    private double configuredSpeed = DEFAULT_SPEED;
    private boolean running;
    private boolean routeDirty = true;
    private StopReason stopReason = StopReason.STOPPED;

    TrainConsist(UUID id) { this.id = id; }

    public UUID id() { return id; }
    public TrackRoute route() { return route; }
    public double progress() { return progress; }
    public double configuredSpeed() { return configuredSpeed; }
    public double actualSpeed() { return running ? configuredSpeed : 0; }
    public boolean running() { return running; }
    public boolean routeDirty() { return routeDirty; }
    public StopReason stopReason() { return stopReason; }

    TrackPathSampler sampler() { return sampler; }
    boolean usesNode(UUID id) { return routeNodes.contains(id); }

    void setRoute(TrackRoute route, TrackPathSampler sampler, double progress, Set<UUID> nodes) {
        this.route = route;
        this.sampler = sampler;
        this.progress = progress;
        this.routeNodes = Set.copyOf(nodes);
        this.routeDirty = false;
    }

    void setProgress(double progress) { this.progress = progress; }
    void setConfiguredSpeed(double speed) { configuredSpeed = speed; }
    void start() { running = true; }

    void stop(StopReason reason) {
        running = false;
        stopReason = reason;
    }

    void invalidate() {
        routeDirty = true;
        stop(StopReason.TRACK_CHANGED);
    }
}
