/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;

/** Owned chunk-local triangles. Material indices refer to the prepared rail's source batch list. */
public record RailTrackMeshData(TrackVector origin, TrackMeshData.Bounds bounds, List<Batch> batches) {
    public record Batch(int sourceIndex, IndexedMesh mesh) {}
    public RailTrackMeshData { batches = List.copyOf(batches); }
}
