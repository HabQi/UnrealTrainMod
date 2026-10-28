/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** One segment/chunk cache entry, with budgeted static uploads and its own material pass. */
interface TrackRenderMesh extends AutoCloseable {
    AABB bounds();
    TrackVector origin();
    boolean isEmpty();
    boolean isUploaded();
    int upload(int budget);
    void draw(Matrix4f modelView, Matrix4f projection);
    @Override void close();
}
