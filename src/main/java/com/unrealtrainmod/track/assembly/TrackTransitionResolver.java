/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.TrackGraph;
import java.util.UUID;

public final class TrackTransitionResolver {
    private TrackTransitionResolver() {}

    public static boolean active(TrackGraph graph, UUID segment) {
        var assembly = graph.assemblyForSegment(segment);
        return assembly == null || assembly.activeSegment().equals(segment);
    }

    public static boolean permits(TrackGraph graph, UUID node, UUID incoming, UUID outgoing) {
        if (!active(graph, incoming) || !active(graph, outgoing)) return false;
        var assembly = graph.assemblyAtPort(node);
        // A shared toe is not a connection between the two internal paths, even in explicit routes.
        return assembly == null || !(assembly.segments().contains(incoming) && assembly.segments().contains(outgoing));
    }
}
