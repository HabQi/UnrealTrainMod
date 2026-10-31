/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Built-in horizontal structures; ordinary turnout dimensions scale with gauge. */
public enum TrackAssemblyDefinition {
    LEFT(TurnoutDirection.LEFT), RIGHT(TurnoutDirection.RIGHT), CUSTOM(null), DIAMOND_CROSSING(null);

    public static final double ANGLE = Math.atan(1.0 / 8);
    private static final Map<String, TrackRouteDefinition> ROUTES = Map.of(
            "straight", new TrackRouteDefinition("A", "B", Map.of("main", SwitchPosition.NORMAL)),
            "diverging", new TrackRouteDefinition("A", "C", Map.of("main", SwitchPosition.REVERSE)));
    private static final JunctionState INITIAL_STATE = new JunctionState(Map.of("main", SwitchPosition.NORMAL));
    private static final Map<String, TrackRouteDefinition> CROSSING_ROUTES = Map.of(
            "line_one", new TrackRouteDefinition("A", "B", Map.of()),
            "line_two", new TrackRouteDefinition("C", "D", Map.of()));
    private static final JunctionState NO_CONTROLS = new JunctionState(Map.of());
    private final TurnoutDirection direction;

    TrackAssemblyDefinition(TurnoutDirection direction) { this.direction = direction; }

    public TrackAssemblyType type() {
        return this == DIAMOND_CROSSING ? TrackAssemblyType.DIAMOND_CROSSING : TrackAssemblyType.SIMPLE_TURNOUT;
    }
    public TurnoutDirection direction() { return direction; }
    public Set<String> portNames() { return this == DIAMOND_CROSSING ? Set.of("A", "B", "C", "D") : Set.of("A", "B", "C"); }
    public Map<String, TrackRouteDefinition> routes() { return this == DIAMOND_CROSSING ? CROSSING_ROUTES : ROUTES; }
    public JunctionState initialState() { return this == DIAMOND_CROSSING ? NO_CONTROLS : INITIAL_STATE; }
    public boolean supportsTrailingPass() { return type() == TrackAssemblyType.SIMPLE_TURNOUT; }

    public void validateState(JunctionState state) {
        if (!state.controls().keySet().equals(initialState().controls().keySet())) {
            throw new IllegalArgumentException("Track controls do not match definition");
        }
    }

    public record PortGeometry(TrackVector position, double outwardYaw) {}
    public record Geometry(Map<String, PortGeometry> ports, Map<String, List<TrackGeometry>> paths) {
        public Geometry {
            ports = Map.copyOf(ports);
            paths = paths.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, e -> List.copyOf(e.getValue())));
        }
    }

    public Geometry geometry(TrackVector origin, double yaw, double gauge) {
        if (direction == null) throw new IllegalArgumentException("This structure requires explicit geometry");
        if (!Double.isFinite(yaw) || !Double.isFinite(gauge) || gauge <= 0 || 32 * gauge / 1.435 > 256
                || !Double.isFinite(origin.x()) || !Double.isFinite(origin.y()) || !Double.isFinite(origin.z())) {
            throw new IllegalArgumentException("Invalid turnout geometry parameters");
        }
        yaw = ((yaw % 360) + 540) % 360 - 180;
        int side = direction.side();
        double length = 32 * gauge / 1.435;
        double radius = length / Math.sin(ANGLE);
        double handle = radius * 4 / 3 * Math.tan(ANGLE / 4);
        double offset = -side * radius * (1 - Math.cos(ANGLE));
        TrackVector b = transform(origin, yaw, 0, length);
        TrackVector c = transform(origin, yaw, offset, length);
        TrackVector p1 = transform(origin, yaw, 0, handle);
        TrackVector p2 = transform(origin, yaw, offset + side * handle * Math.sin(ANGLE),
                length - handle * Math.cos(ANGLE));
        TrackPoint a = new TrackPoint(origin.x(), origin.z());
        TrackPoint c1 = new TrackPoint(p1.x(), p1.z()), c2 = new TrackPoint(p2.x(), p2.z());
        var grade = new LinearGrade(origin.y(), origin.y());
        return new Geometry(Map.of("A", new PortGeometry(origin, yaw + 180),
                "B", new PortGeometry(b, yaw), "C", new PortGeometry(c, exitYaw(yaw))),
                Map.of("straight", List.of(new StraightTrackGeometry(a, new TrackPoint(b.x(), b.z()), grade, gauge)),
                        "diverging", List.of(new BezierTrackGeometry(a, c1, c2, new TrackPoint(c.x(), c.z()), grade, gauge))));
    }

    public double exitYaw(double yaw) { return yaw + direction.side() * Math.toDegrees(ANGLE); }

    private static TrackVector transform(TrackVector origin, double yaw, double x, double z) {
        double angle = Math.toRadians(yaw);
        return new TrackVector(origin.x() + x * Math.cos(angle) - z * Math.sin(angle), origin.y(),
                origin.z() + x * Math.sin(angle) + z * Math.cos(angle));
    }
}
