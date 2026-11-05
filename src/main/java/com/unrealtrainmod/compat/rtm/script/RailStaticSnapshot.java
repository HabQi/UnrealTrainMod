/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/** Java-only chunk generation from a single invocation of each static script entry. */
public final class RailStaticSnapshot {
    private final TrackGeometry geometry;
    private final BlockPos start;
    private final double scale, period;
    private final List<Consumer<RailScriptDrawContext>> commands;

    RailStaticSnapshot(TrackGeometry geometry, BlockPos start, double scale, double period,
                       List<Consumer<RailScriptDrawContext>> commands) {
        this.geometry = geometry;
        this.start = start;
        this.scale = scale;
        this.period = period;
        this.commands = List.copyOf(commands);
    }

    public RailTrackMeshData build(int chunkX, int chunkZ) {
        var context = new RailScriptDrawContext(geometry, start, chunkX, chunkZ, scale, period, false,
                RailTrackMeshBuilder.Filter.ALL);
        try {
            for (var command : commands) command.accept(context);
            return context.finish();
        } finally { context.restore(); }
    }
}
