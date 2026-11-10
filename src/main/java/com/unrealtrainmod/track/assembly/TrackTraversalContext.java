/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import java.util.Set;
import java.util.UUID;

/** Read-only permissions. A candidate route never acquires or throws a turnout. */
public record TrackTraversalContext(boolean candidates, boolean forward, Set<UUID> occupiedSegments) {
    public static final TrackTraversalContext STRICT = new TrackTraversalContext(false, true, Set.of());
    public TrackTraversalContext {
        occupiedSegments = Set.copyOf(occupiedSegments);
    }
    public TrackTraversalContext reversed() { return new TrackTraversalContext(candidates, !forward, occupiedSegments); }

    public boolean allows(TrackAssemblyInstance assembly, String pathId, boolean reversedPath) {
        if (TrackTransitionResolver.isPathEnabled(assembly, pathId)) return true;
        if (!assembly.definition().supportsTrailingPass()) return false;
        // A committed approach remains usable when stopping, reversing, splitting or restoring.
        // Only the movement proposal at the leading bogie's trigger can actually throw the switch.
        if (assembly.paths().get(pathId).steps().stream().anyMatch(step -> occupiedSegments.contains(step.segmentId()))) return true;
        return candidates && (forward == reversedPath);
    }
}
