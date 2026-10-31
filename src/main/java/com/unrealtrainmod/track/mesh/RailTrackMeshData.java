/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Owned chunk-local triangles with the material state needed to draw them. */
public record RailTrackMeshData(TrackVector origin, TrackMeshData.Bounds bounds, List<Batch> batches) {
    public record Batch(ResourceLocation texture, boolean doubleSided, IndexedMesh mesh) {}
    public RailTrackMeshData { batches = List.copyOf(batches); }
}
