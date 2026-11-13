/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import java.util.List;
import java.util.Map;

/** CPU layout survives backend changes. The owner retains static chunk sources and its control timeline. */
final class NativeTurnoutRenderState implements AutoCloseable {
    final NativeTurnoutLayout layout;
    final RailPartRenderer parts;
    private final Map<String, List<Source>> sources;
    TrackPartSnapshot snapshot;
    private float movement = Float.NaN;

    NativeTurnoutRenderState(NativeTurnoutLayout layout, ClientRailModelManager.Model model) {
        this.layout = layout;
        sources = NativeTurnoutModels.sources(model.parts());
        for (var part : layout.parts()) if (!sources.containsKey(part.name())) {
            throw new IllegalArgumentException("Missing native r11 part: " + part.name());
        }
        parts = new RailPartRenderer(layout.origin(), Math.floor(layout.origin().y() / 16) * 16, true);
    }

    void movement(float value) {
        if (movement == value) return;
        snapshot = NativeTurnoutMeshes.moving(layout, sources, value);
        movement = value;
    }

    TrackStaticMesh build(long chunk) {
        return new RailTrackGpuMesh(NativeTurnoutMeshes.build(layout, sources, chunk, false, 0));
    }

    void invalidateGpu() { parts.invalidateInstances(); }
    @Override public void close() { parts.close(); snapshot = null; }
}
