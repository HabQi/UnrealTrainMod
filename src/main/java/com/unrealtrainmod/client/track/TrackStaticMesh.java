/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

/** Immutable CPU source retained across GPU backend changes. */
abstract class TrackStaticMesh implements TrackRenderMesh {
    abstract void register(TrackStaticBuffers buffers, int structure);

    @Override public final void close() {
        TrackStaticBuffers.WORLD.remove(this);
    }
}
