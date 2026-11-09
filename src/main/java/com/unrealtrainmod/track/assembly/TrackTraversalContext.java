/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only permissions. A candidate route never acquires or throws a turnout. */
public record TrackTraversalContext(List<UUID> members, boolean candidates, boolean forward, Set<UUID> occupiedSegments) {
    public static final TrackTraversalContext STRICT = new TrackTraversalContext(List.of(), false, true, Set.of());
    public TrackTraversalContext {
        members = List.copyOf(members);
        occupiedSegments = Set.copyOf(occupiedSegments);
    }
    public TrackTraversalContext reversed() { return new TrackTraversalContext(members, candidates, !forward, occupiedSegments); }
    public boolean owns(TrackAssemblyInstance assembly) { return assembly.occupant() != null && members.contains(assembly.occupant()); }

    public boolean allows(TrackAssemblyInstance assembly, String pathId, boolean reversedPath) {
        var passage = assembly.trailingPass();
        // Uncoupling/destruction preserves an existing passage for every fragment on that path.
        // Physical admission is checked separately against committed footprints, before any move.
        if (passage != null && (owns(assembly) || assembly.paths().get(passage.pathId()).steps().stream()
                .anyMatch(step -> occupiedSegments.contains(step.segmentId())))) return passage.pathId().equals(pathId);
        if (TrackTransitionResolver.isPathEnabled(assembly, pathId)) return true;
        return assembly.definition().supportsTrailingPass() && passage == null && candidates && (forward == reversedPath);
    }
}
