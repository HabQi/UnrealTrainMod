/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.unrealtrainmod.track.assembly.SwitchPosition.NORMAL;
import static com.unrealtrainmod.track.assembly.SwitchPosition.REVERSE;

/** Named horizontal paths and their control requirements. */
public enum TrackAssemblyDefinition {
    CUSTOM, DIAMOND_CROSSING, SINGLE_CROSSOVER, SCISSORS_CROSSOVER, DOUBLE_SLIP;

    public static final String DOUBLE_SLIP_MACHINE_ONE = "machine_one";
    public static final String DOUBLE_SLIP_MACHINE_TWO = "machine_two";
    public static final String DOUBLE_SLIP_MACHINE_THREE = "machine_three";
    public static final String DOUBLE_SLIP_MACHINE_FOUR = "machine_four";

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
    private static final Map<String, TrackRouteDefinition> SCISSORS_ROUTES = Map.of(
            "first_straight", new TrackRouteDefinition("A", "B", Map.of("switch_one", SwitchPosition.NORMAL, "switch_two", SwitchPosition.NORMAL)),
            "second_straight", new TrackRouteDefinition("C", "D", Map.of("switch_three", SwitchPosition.NORMAL, "switch_four", SwitchPosition.NORMAL)),
            "first_crossover", new TrackRouteDefinition("A", "D", Map.of("switch_one", SwitchPosition.REVERSE, "switch_four", SwitchPosition.REVERSE)),
            "second_crossover", new TrackRouteDefinition("C", "B", Map.of("switch_three", SwitchPosition.REVERSE, "switch_two", SwitchPosition.REVERSE)));
    private static final JunctionState SCISSORS_STATE = new JunctionState(Map.of(
            "switch_one", SwitchPosition.NORMAL, "switch_two", SwitchPosition.NORMAL,
            "switch_three", SwitchPosition.NORMAL, "switch_four", SwitchPosition.NORMAL));
    private static final Map<String, TrackRouteDefinition> DOUBLE_SLIP_ROUTES = doubleSlipRoutes();
    private static final JunctionState DOUBLE_SLIP_STATE = new JunctionState(DOUBLE_SLIP_ROUTES.get("first_straight").requirements());
    private static final JunctionState NO_CONTROLS = new JunctionState(Map.of());

    private static Map<String, TrackRouteDefinition> doubleSlipRoutes() {
        var routes = new LinkedHashMap<String, TrackRouteDefinition>();
        routes.put("first_straight", doubleSlipStraight("A", "B", NORMAL));
        routes.put("second_straight", doubleSlipStraight("C", "D", REVERSE));
        // Slip routes branch at the ends and leave both central machines in their last through position.
        routes.put("first_crossover", new TrackRouteDefinition("A", "D", Map.of(
                DOUBLE_SLIP_MACHINE_ONE, REVERSE, DOUBLE_SLIP_MACHINE_FOUR, NORMAL)));
        routes.put("second_crossover", new TrackRouteDefinition("C", "B", Map.of(
                DOUBLE_SLIP_MACHINE_ONE, NORMAL, DOUBLE_SLIP_MACHINE_FOUR, REVERSE)));
        return Collections.unmodifiableMap(routes);
    }

    private static TrackRouteDefinition doubleSlipStraight(String entrance, String exit, SwitchPosition position) {
        return new TrackRouteDefinition(entrance, exit, Map.of(
                DOUBLE_SLIP_MACHINE_ONE, position, DOUBLE_SLIP_MACHINE_TWO, position,
                DOUBLE_SLIP_MACHINE_THREE, position, DOUBLE_SLIP_MACHINE_FOUR, position));
    }

    public TrackAssemblyType type() {
        return switch (this) {
            case DIAMOND_CROSSING -> TrackAssemblyType.DIAMOND_CROSSING;
            case SINGLE_CROSSOVER -> TrackAssemblyType.SINGLE_CROSSOVER;
            case SCISSORS_CROSSOVER -> TrackAssemblyType.SCISSORS_CROSSOVER;
            case DOUBLE_SLIP -> TrackAssemblyType.DOUBLE_SLIP;
            default -> TrackAssemblyType.SIMPLE_TURNOUT;
        };
    }
    public Set<String> portNames() {
        return this != CUSTOM ? Set.of("A", "B", "C", "D") : Set.of("A", "B", "C");
    }
    public Map<String, TrackRouteDefinition> routes() {
        return switch (this) {
            case SCISSORS_CROSSOVER -> SCISSORS_ROUTES;
            case DOUBLE_SLIP -> DOUBLE_SLIP_ROUTES;
            case DIAMOND_CROSSING -> CROSSING_ROUTES;
            case SINGLE_CROSSOVER -> CROSSOVER_ROUTES;
            case CUSTOM -> ROUTES;
        };
    }
    public JunctionState initialState() {
        return switch (this) {
            case SCISSORS_CROSSOVER -> SCISSORS_STATE;
            case DOUBLE_SLIP -> DOUBLE_SLIP_STATE;
            case DIAMOND_CROSSING -> NO_CONTROLS;
            case SINGLE_CROSSOVER -> CROSSOVER_STATE;
            case CUSTOM -> INITIAL_STATE;
        };
    }
    public boolean supportsTrailingPass() { return type() == TrackAssemblyType.SIMPLE_TURNOUT; }

    public static JunctionState doubleSlipState(String path, JunctionState current) {
        var route = DOUBLE_SLIP_ROUTES.get(path);
        if (route == null) throw new IllegalArgumentException("Unknown double slip route: " + path);
        var controls = new LinkedHashMap<>(current.controls());
        controls.putAll(route.requirements());
        return new JunctionState(controls);
    }

    public static String nextDoubleSlipPath(JunctionState state) {
        var order = List.copyOf(DOUBLE_SLIP_ROUTES.keySet());
        for (int i = 0; i < order.size(); i++) {
            if (DOUBLE_SLIP_ROUTES.get(order.get(i)).enabled(state)) return order.get((i + 1) % order.size());
        }
        throw new IllegalArgumentException("No selected double slip route");
    }

    public static Set<String> doubleSlipPortControls() { return SCISSORS_STATE.controls().keySet(); }

    /** A legacy control command selects the route incident to that port in the requested position. */
    public static JunctionState doubleSlipStateForControl(String control, SwitchPosition position, JunctionState current) {
        for (var path : DOUBLE_SLIP_ROUTES.keySet()) {
            if (SCISSORS_ROUTES.get(path).requirements().get(control) == position) return doubleSlipState(path, current);
        }
        throw new IllegalArgumentException("Unknown double slip control: " + control);
    }

    JunctionState normalizeLoadedState(JunctionState state) {
        if (this != DOUBLE_SLIP || !state.controls().keySet().equals(doubleSlipPortControls())) return state;

        // Port-based saves have no last through position to restore when a slip route is selected.
        for (var path : DOUBLE_SLIP_ROUTES.keySet()) {
            if (SCISSORS_ROUTES.get(path).enabled(state)) return doubleSlipState(path, DOUBLE_SLIP_STATE);
        }
        return DOUBLE_SLIP_STATE;
    }

    public void validateState(JunctionState state) {
        if (!state.controls().keySet().equals(initialState().controls().keySet())) {
            throw new IllegalArgumentException("Track controls do not match definition");
        }
        if (this == DOUBLE_SLIP) {
            if (state.controls().get(DOUBLE_SLIP_MACHINE_TWO) != state.controls().get(DOUBLE_SLIP_MACHINE_THREE)) {
                throw new IllegalArgumentException("Double slip central machines must select the same through line");
            }
            if (DOUBLE_SLIP_ROUTES.values().stream().noneMatch(route -> route.enabled(state))) {
                throw new IllegalArgumentException("Double slip controls do not select a route");
            }
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
