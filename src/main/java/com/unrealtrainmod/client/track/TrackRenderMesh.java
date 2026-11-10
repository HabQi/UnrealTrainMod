/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.world.phys.AABB;

/** A chunk-owned source; static range views never own their shared GPU pages. */
interface TrackRenderMesh extends AutoCloseable {
    AABB bounds();
    TrackVector origin();
    boolean isEmpty();
    int source();
    @Override void close();
}
