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
    private final Map<NativeTurnoutLayout.Part, List<Source>> sources;
    TrackPartSnapshot snapshot;
    private float movement = Float.NaN;
    private boolean closed;

    NativeTurnoutRenderState(NativeTurnoutLayout layout, ClientRailModelManager.Model model) {
        this.layout = layout;
        sources = NativeTurnoutMeshes.prepare(layout, NativeTurnoutModels.sources(model.parts()));
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
    @Override public void close() {
        if (closed) return;
        closed = true; parts.close(); snapshot = null; NativeTurnoutMeshes.release(sources);
    }
}
