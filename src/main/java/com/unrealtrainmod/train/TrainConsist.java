/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent members, coupler chain and server-owned runtime state for a complete consist. */
public final class TrainConsist {
    public enum StopReason {
        STOPPED, END_OF_ROUTE, UNLOADED_AREA, INVALID_POSITION, TRACK_CHANGED,
        INVALID_ANCHOR, MISSING_MODEL, STORAGE_ERROR, UNBOUND,
        INSUFFICIENT_ROUTE, DEGENERATE_GEOMETRY, TURNOUT_OCCUPIED, TURNOUT_ROUTE_UNAVAILABLE,
        MEMBER_MISMATCH, INCOMPLETE_CONSIST, CONSIST_NOT_STOPPED, CONSIST_NOT_READY,
        COUPLING_REJECTED, UNCOUPLE_REQUIRED, TURNOUT_OPERATION_BLOCKED, NOT_COUPLED, DRIVER_PRESENT
    }

    public static final double DEFAULT_SPEED = 4;
    public static final double MAX_SPEED = 100;
    private final UUID id;
    private List<TrainCarState> cars;
    // Null only for a legacy two-car record awaiting model-dependent end migration.
    private List<TrainCoupling> couplings;
    private Map<TrainCoupling.End, TrainCoupling.End> peers = Map.of();
    private TrainRouteWindow window;
    private double configuredSpeed;
    private boolean simulationActive;
    private boolean commandControlled;
    private double commandTarget;
    private TrainControlState control;
    private final TrainDynamicsState dynamics = new TrainDynamicsState();
    private boolean routeDirty = true;
    private StopReason stopReason = StopReason.STOPPED;

    TrainConsist(UUID id, List<TrainCarState> cars, double configuredSpeed) {
        this(id, cars, configuredSpeed, List.of());
    }

    TrainConsist(UUID id, List<TrainCarState> cars, double configuredSpeed, List<TrainCoupling> couplings) {
        this.id = id;
        this.cars = List.copyOf(cars);
        setCouplings(couplings);
        this.configuredSpeed = configuredSpeed;
        this.control = new TrainControlState(configuredSpeed < 0 ? TrainControlState.Reverser.REVERSE
                : TrainControlState.Reverser.FORWARD, 0, 1, false);
        dynamics.resetBrakes(this.cars);
    }

    public UUID id() { return id; }
    public List<TrainCarState> cars() { return cars; }
    public List<TrainCoupling> couplings() { return couplings; }
    public boolean couplingsReady() { return couplings != null; }
    public TrainCoupling.End peer(UUID vehicle, TrainCarEnd end) {
        return peers.get(new TrainCoupling.End(vehicle, end));
    }
    void setCouplings(List<TrainCoupling> value) {
        couplings = value == null ? null : List.copyOf(value);
        var endpoints = new HashMap<TrainCoupling.End, TrainCoupling.End>();
        if (couplings != null) for (var link : couplings) {
            endpoints.put(link.first(), link.second());
            endpoints.put(link.second(), link.first());
        }
        peers = Map.copyOf(endpoints);
    }
    public TrainAnchor anchor() { return cars.getFirst().anchor(); }
    public TrainCarState member(UUID entityId) {
        return cars.stream().filter(car -> car.entityId().equals(entityId)).findFirst().orElse(null);
    }
    public TrackRoute route() { return window == null ? null : window.route(); }
    public double progress() { return window == null ? 0 : window.progress(); }
    public double configuredSpeed() { return configuredSpeed; }
    public double actualSpeed() { return dynamics.speed(); }
    public boolean running() { return actualSpeed() != 0; }
    public TrainControlState control() { return control; }
    public TrainDynamicsState dynamics() { return dynamics; }
    boolean simulationActive() { return simulationActive; }
    boolean commandControlled() { return commandControlled; }
    double commandTarget() { return commandTarget; }

    int planningDirection() {
        if (actualSpeed() != 0) return actualSpeed() > 0 ? 1 : -1;
        if (commandControlled && commandTarget != 0) return commandTarget > 0 ? 1 : -1;
        return control.reverser().direction() != 0 ? control.reverser().direction() : configuredSpeed > 0 ? 1 : -1;
    }

    double planningSpeed() {
        double target = commandControlled ? Math.abs(commandTarget) : 0;
        double magnitude = Math.max(Math.abs(actualSpeed()), target);
        // Stable lookahead buckets avoid rebuilding the route for every accelerating tick.
        return planningDirection() * Math.max(16, Math.ceil(magnitude / 16) * 16);
    }
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
    void startCommand(double target) {
        commandTarget = target;
        commandControlled = true;
        simulationActive = true;
        stopReason = StopReason.STOPPED;
    }

    void setControl(TrainControlState control) {
        if (!this.control.equals(control)) stopReason = StopReason.STOPPED;
        this.control = control;
    }

    void startManual(TrainControlState control) {
        commandControlled = false;
        simulationActive = true;
        this.control = control;
        stopReason = StopReason.STOPPED;
    }

    void safetyBrake() {
        startManual(new TrainControlState(control.reverser(), 0, 1, control.emergencyBrake()));
    }

    void stop(StopReason reason) {
        stop(reason, false);
    }

    void stop(StopReason reason, boolean keepSimulationActive) {
        simulationActive = keepSimulationActive;
        commandControlled = false;
        dynamics.stop();
        control = new TrainControlState(control.reverser(), 0, 1, false);
        stopReason = reason;
    }

    void invalidate() {
        routeDirty = true;
    }

    void clearRoute() {
        window = null;
        routeDirty = true;
    }
}
