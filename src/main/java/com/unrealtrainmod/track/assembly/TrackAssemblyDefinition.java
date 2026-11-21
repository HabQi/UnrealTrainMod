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
    private static final JunctionState DOUBLE_SLIP_STATE = doubleSlipState("first_straight");
    private static final JunctionState NO_CONTROLS = new JunctionState(Map.of());

    private static Map<String, TrackRouteDefinition> doubleSlipRoutes() {
        var routes = new LinkedHashMap<String, TrackRouteDefinition>();
        routes.put("first_straight", doubleSlipRoute("A", "B", NORMAL, NORMAL, REVERSE, REVERSE));
        routes.put("second_straight", doubleSlipRoute("C", "D", REVERSE, REVERSE, NORMAL, NORMAL));
        routes.put("first_crossover", doubleSlipRoute("A", "D", REVERSE, NORMAL, NORMAL, REVERSE));
        routes.put("second_crossover", doubleSlipRoute("C", "B", NORMAL, REVERSE, REVERSE, NORMAL));
        return Collections.unmodifiableMap(routes);
    }

    private static TrackRouteDefinition doubleSlipRoute(String entrance, String exit,
            SwitchPosition a, SwitchPosition b, SwitchPosition c, SwitchPosition d) {
        return new TrackRouteDefinition(entrance, exit, Map.of(
                "switch_one", a, "switch_two", b, "switch_three", c, "switch_four", d));
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

    public static JunctionState doubleSlipState(String path) {
        var route = DOUBLE_SLIP_ROUTES.get(path);
        if (route == null) throw new IllegalArgumentException("Unknown double slip route: " + path);
        return new JunctionState(route.requirements());
    }

    public static String nextDoubleSlipPath(JunctionState state) {
        var order = List.copyOf(DOUBLE_SLIP_ROUTES.keySet());
        for (int i = 0; i < order.size(); i++) {
            if (DOUBLE_SLIP_ROUTES.get(order.get(i)).enabled(state)) return order.get((i + 1) % order.size());
        }
        throw new IllegalArgumentException("No selected double slip route");
    }

    /** A legacy control command selects the route incident to that port in the requested position. */
    public static JunctionState doubleSlipStateForControl(String control, SwitchPosition position) {
        for (var path : DOUBLE_SLIP_ROUTES.keySet()) {
            if (SCISSORS_ROUTES.get(path).requirements().get(control) == position) return doubleSlipState(path);
        }
        throw new IllegalArgumentException("Unknown double slip control: " + control);
    }

    JunctionState normalizeLoadedState(JunctionState state) {
        if (this != DOUBLE_SLIP || !state.controls().keySet().equals(DOUBLE_SLIP_STATE.controls().keySet())
                || DOUBLE_SLIP_ROUTES.values().stream().anyMatch(route -> route.enabled(state))) return state;

        // Older double slips shared the two-control route conditions of scissors crossovers.
        for (var path : DOUBLE_SLIP_ROUTES.keySet()) {
            if (SCISSORS_ROUTES.get(path).enabled(state)) return doubleSlipState(path);
        }
        return DOUBLE_SLIP_STATE;
    }

    public void validateState(JunctionState state) {
        if (!state.controls().keySet().equals(initialState().controls().keySet())) {
            throw new IllegalArgumentException("Track controls do not match definition");
        }
        if (this == DOUBLE_SLIP && DOUBLE_SLIP_ROUTES.values().stream().noneMatch(route -> route.enabled(state))) {
            throw new IllegalArgumentException("Double slip controls do not select a route");
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
