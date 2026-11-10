/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import java.util.UUID;

/** Client script identity and geometry only: no graph nodes, segments, controls or saved ownership. */
public record RailTurnoutView(UUID owner, String end, String control, TrackGeometry main,
                              TrackGeometry branch, boolean ownsBranchEnd) {
    public TrackVector origin() { return main.sample(0).position(); }

    public static List<RailTurnoutView> of(TrackAssemblyInstance assembly) {
        var paths = assembly.geometry().paths();
        return switch (assembly.definition()) {
            case CUSTOM -> List.of(new RailTurnoutView(assembly.id(), "A", "main",
                    paths.get("straight").getFirst(), paths.get("diverging").getFirst(), true));
            case SINGLE_CROSSOVER -> {
                var crossover = paths.get("crossover").getFirst();
                double middle = crossover.getLength() / 2;
                yield List.of(new RailTurnoutView(assembly.id(), "A", "switch_one",
                                paths.get("first_straight").getFirst(), crossover.section(0, middle), true),
                        new RailTurnoutView(assembly.id(), "D", "switch_two",
                                paths.get("second_straight").getFirst().reversed(),
                                crossover.section(middle, crossover.getLength()).reversed(), false));
            }
            default -> List.of();
        };
    }
}
