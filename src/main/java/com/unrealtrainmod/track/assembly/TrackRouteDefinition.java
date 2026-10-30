/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import java.util.Map;

/** Both directions of a logical path require the same control positions. */
public record TrackRouteDefinition(String entrance, String exit, Map<String, SwitchPosition> requirements) {
    public TrackRouteDefinition { requirements = Map.copyOf(requirements); }

    public boolean enabled(JunctionState state) {
        return requirements.entrySet().stream().allMatch(e -> state.controls().get(e.getKey()) == e.getValue());
    }
}
