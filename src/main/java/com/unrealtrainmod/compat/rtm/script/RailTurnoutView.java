/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.UUID;

/** Client script identity and geometry only: no graph nodes, segments, controls or saved ownership. */
public record RailTurnoutView(UUID owner, String control, TrackGeometry main,
                              TrackGeometry branch) {
    public TrackVector origin() { return main.sample(0).position(); }

    public static RailTurnoutView of(TrackAssemblyInstance assembly) {
        if (assembly.definition() != TrackAssemblyDefinition.CUSTOM) return null;
        var paths = assembly.geometry().paths();
        return new RailTurnoutView(assembly.id(), "main",
                paths.get("straight").getFirst(), paths.get("diverging").getFirst());
    }
}
