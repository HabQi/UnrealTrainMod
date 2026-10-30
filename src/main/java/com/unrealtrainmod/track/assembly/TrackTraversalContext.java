/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import java.util.List;
import java.util.UUID;

/** Read-only permissions. A candidate route never acquires or throws a turnout. */
public record TrackTraversalContext(List<UUID> members, boolean candidates, boolean forward) {
    public static final TrackTraversalContext STRICT = new TrackTraversalContext(List.of(), false, true);
    public TrackTraversalContext { members = List.copyOf(members); }
    public TrackTraversalContext reversed() { return new TrackTraversalContext(members, candidates, !forward); }
    public boolean owns(TrackAssemblyInstance assembly) { return assembly.occupant() != null && members.contains(assembly.occupant()); }

    public boolean allows(TrackAssemblyInstance assembly, String pathId, boolean reversedPath) {
        var passage = assembly.trailingPass();
        if (passage != null && owns(assembly)) return passage.pathId().equals(pathId);
        if (TrackTransitionResolver.isPathEnabled(assembly, pathId)) return true;
        return passage == null && candidates && (forward == reversedPath);
    }
}
