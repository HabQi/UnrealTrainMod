/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Named horizontal paths and their control requirements. */
public enum TrackAssemblyDefinition {
    CUSTOM, DIAMOND_CROSSING, SINGLE_CROSSOVER;

    private static final Map<String, TrackRouteDefinition> ROUTES = Map.of(
            "straight", new TrackRouteDefinition("A", "B", Map.of("main", SwitchPosition.NORMAL)),
            "diverging", new TrackRouteDefinition("A", "C", Map.of("main", SwitchPosition.REVERSE)));
    private static final JunctionState INITIAL_STATE = new JunctionState(Map.of("main", SwitchPosition.NORMAL));
    private static final Map<String, TrackRouteDefinition> CROSSING_ROUTES = Map.of(
            "line_one", new TrackRouteDefinition("A", "B", Map.of()),
            "line_two", new TrackRouteDefinition("C", "D", Map.of()));
    private static final Map<String, TrackRouteDefinition> CROSSOVER_ROUTES = Map.of(
            "first_straight", new TrackRouteDefinition("A", "B", Map.of("switch_one", SwitchPosition.NORMAL)),
            "second_straight", new TrackRouteDefinition("C", "D", Map.of("switch_two", SwitchPosition.NORMAL)),
            "crossover", new TrackRouteDefinition("A", "D", Map.of(
                    "switch_one", SwitchPosition.REVERSE, "switch_two", SwitchPosition.REVERSE)));
    private static final JunctionState CROSSOVER_STATE = new JunctionState(Map.of(
            "switch_one", SwitchPosition.NORMAL, "switch_two", SwitchPosition.NORMAL));
    private static final JunctionState NO_CONTROLS = new JunctionState(Map.of());
    public TrackAssemblyType type() {
        return switch (this) {
            case DIAMOND_CROSSING -> TrackAssemblyType.DIAMOND_CROSSING;
            case SINGLE_CROSSOVER -> TrackAssemblyType.SINGLE_CROSSOVER;
            default -> TrackAssemblyType.SIMPLE_TURNOUT;
        };
    }
    public Set<String> portNames() {
        return this == DIAMOND_CROSSING || this == SINGLE_CROSSOVER ? Set.of("A", "B", "C", "D") : Set.of("A", "B", "C");
    }
    public Map<String, TrackRouteDefinition> routes() {
        return this == DIAMOND_CROSSING ? CROSSING_ROUTES : this == SINGLE_CROSSOVER ? CROSSOVER_ROUTES : ROUTES;
    }
    public JunctionState initialState() {
        return this == DIAMOND_CROSSING ? NO_CONTROLS : this == SINGLE_CROSSOVER ? CROSSOVER_STATE : INITIAL_STATE;
    }
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

}
