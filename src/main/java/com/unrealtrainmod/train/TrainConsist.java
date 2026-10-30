/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import java.util.List;
import java.util.UUID;

/** Persistent ordered membership and server-owned runtime state for one or two same-facing cars. */
public final class TrainConsist {
    public enum StopReason {
        STOPPED, END_OF_ROUTE, UNLOADED_AREA, INVALID_POSITION, TRACK_CHANGED,
        INVALID_ANCHOR, MISSING_MODEL, STORAGE_ERROR, UNBOUND,
        INSUFFICIENT_ROUTE, DEGENERATE_GEOMETRY, TURNOUT_OCCUPIED, TURNOUT_ROUTE_UNAVAILABLE,
        MEMBER_MISMATCH, INCOMPLETE_CONSIST, CONSIST_NOT_STOPPED, CONSIST_NOT_READY,
        COUPLING_REJECTED, UNCOUPLE_REQUIRED, TURNOUT_OPERATION_BLOCKED, NOT_COUPLED
    }

    public static final double DEFAULT_SPEED = 4;
    public static final double MAX_SPEED = 100;
    private final UUID id;
    private List<TrainCarState> cars;
    private TrainRouteWindow window;
    private double configuredSpeed;
    private boolean running;
    private boolean routeDirty = true;
    private StopReason stopReason = StopReason.STOPPED;

    TrainConsist(UUID id, List<TrainCarState> cars, double configuredSpeed) {
        this.id = id;
        this.cars = List.copyOf(cars);
        this.configuredSpeed = configuredSpeed;
    }

    public UUID id() { return id; }
    public List<TrainCarState> cars() { return cars; }
    public TrainAnchor anchor() { return cars.getFirst().anchor(); }
    public TrainCarState member(UUID entityId) {
        return cars.stream().filter(car -> car.entityId().equals(entityId)).findFirst().orElse(null);
    }
    public TrackRoute route() { return window == null ? null : window.route(); }
    public double progress() { return window == null ? 0 : window.progress(); }
    public double configuredSpeed() { return configuredSpeed; }
    public double actualSpeed() { return running ? configuredSpeed : 0; }
    public boolean running() { return running; }
    public boolean routeDirty() { return routeDirty; }
    public StopReason stopReason() { return stopReason; }

    TrainRouteWindow window() { return window; }
    TrackPathSampler sampler() { return window.sampler(); }
    boolean usesNode(UUID id) { return window != null && window.usesNode(id); }
    boolean usesSegment(UUID id) { return window != null && window.usesSegment(id); }

    void setWindow(TrainRouteWindow window) {
        this.window = window;
        this.routeDirty = false;
    }

    void setProgress(double progress) { window.setProgress(progress); }
    void setCars(List<TrainCarState> cars) { this.cars = List.copyOf(cars); }
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

    void clearRoute() {
        window = null;
        routeDirty = true;
    }
}
