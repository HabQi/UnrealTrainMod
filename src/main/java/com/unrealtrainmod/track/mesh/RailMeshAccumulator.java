/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Material merging with bounded batches. A triangle and its vertices always stay in the same batch. */
public final class RailMeshAccumulator {
    private static final int MAX_REFERENCES = 262_143;
    private record Material(ResourceLocation texture, boolean doubleSided) {}
    private static final class Buffer {
        final FloatArrayList vertices = new FloatArrayList();
        final IntArrayList colors = new IntArrayList();
        final IntArrayList indices = new IntArrayList();
    }

    @FunctionalInterface
    public interface VertexWriter {
        /** Append XYZ, UV and normal XYZ, in coordinates relative to the accumulator origin. */
        void write(int index, FloatArrayList output);
    }

    private final TrackVector origin;
    private final LinkedHashMap<Material, List<Buffer>> materials = new LinkedHashMap<>();
    private int[] remap = new int[0], epochs = new int[0];
    private int epoch;
    private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    private double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

    public RailMeshAccumulator(TrackVector origin) { this.origin = origin; }

    public void append(ResourceLocation texture, boolean doubleSided, IndexedMesh mesh, VertexWriter writer) {
        if (mesh.indices().length == 0) return;
        var buffers = materials.computeIfAbsent(new Material(texture, doubleSided), ignored -> new ArrayList<>());
        if (buffers.isEmpty()) buffers.add(new Buffer());
        var output = buffers.getLast();
        if (remap.length < mesh.colors().length) {
            remap = new int[mesh.colors().length];
            epochs = new int[mesh.colors().length];
        }
        int currentEpoch = ++epoch;
        for (int triangle = 0; triangle < mesh.indices().length; triangle += 3) {
            if (output.indices.size() + 3 > MAX_REFERENCES) {
                output = new Buffer();
                buffers.add(output);
                currentEpoch = ++epoch;
            }
            for (int corner = 0; corner < 3; corner++) {
                int index = mesh.indices()[triangle + corner];
                if (epochs[index] != currentEpoch) {
                    epochs[index] = currentEpoch;
                    remap[index] = output.colors.size();
                    int first = output.vertices.size();
                    writer.write(index, output.vertices);
                    for (int i = first; i < first + 8; i++) {
                        if (!Float.isFinite(output.vertices.getFloat(i))) {
                            throw new IllegalArgumentException("Non-finite rail vertex");
                        }
                    }
                    output.colors.add(mesh.colors()[index]);
                    double x = origin.x() + output.vertices.getFloat(first);
                    double y = origin.y() + output.vertices.getFloat(first + 1);
                    double z = origin.z() + output.vertices.getFloat(first + 2);
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
                output.indices.add(remap[index]);
            }
        }
    }

    public RailTrackMeshData finish() {
        var batches = new ArrayList<RailTrackMeshData.Batch>();
        materials.forEach((material, buffers) -> {
            for (var buffer : buffers) {
                batches.add(new RailTrackMeshData.Batch(material.texture(), material.doubleSided(),
                        new IndexedMesh(buffer.vertices.toFloatArray(), buffer.colors.toIntArray(), buffer.indices.toIntArray())));
            }
        });
        var bounds = batches.isEmpty() ? new TrackMeshData.Bounds(0, 0, 0, 0, 0, 0)
                : new TrackMeshData.Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        return new RailTrackMeshData(origin, bounds, List.copyOf(batches));
    }

    public void clear() { materials.clear(); }
}
