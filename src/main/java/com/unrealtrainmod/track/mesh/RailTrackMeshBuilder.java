/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Rail-only static repetition with endpoint tangent extension. No GPU resources; textures are identifiers only. */
public final class RailTrackMeshBuilder {
    private static final int MAX_VERTICES = 262_144;
    /** One prepared model object and its material batch. */
    public record Source(String part, ResourceLocation texture, boolean doubleSided, IndexedMesh mesh) {}
    /** Legacy shouldRenderObject filter; index is the repeated module number and count the module total. */
    public interface Filter {
        Filter ALL = (part, index, count) -> true;
        boolean shouldRender(String part, long index, long count);
    }

    private record MaterialKey(ResourceLocation texture, boolean doubleSided) {}

    private static final class MaterialBuffer {
        final FloatArrayList vertices = new FloatArrayList();
        final IntArrayList colors = new IntArrayList();
        final IntArrayList indices = new IntArrayList();
    }

    private final TrackGeometry geometry;
    private final int chunkX, chunkZ;
    private final double length;
    private final double scale, period;
    private final long count;
    private final TrackVector origin;
    private final Long2ObjectOpenHashMap<TrackPose> poses = new Long2ObjectOpenHashMap<>();
    private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    private double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
    /** Keep the existing limit on submitted references, not just the new shared vertex records. */
    private int vertexCount;

    private RailTrackMeshBuilder(TrackGeometry geometry, int chunkX, int chunkZ, double scale, double referencePeriod) {
        this.geometry = geometry;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.scale = scale;
        period = referencePeriod * scale;
        if (!Double.isFinite(scale) || scale <= 0 || !Double.isFinite(period) || period <= 0) {
            throw new IllegalArgumentException("Rail model scale and period must be finite and positive");
        }
        length = geometry.getLength();
        if (length / period >= Long.MAX_VALUE) throw new IllegalArgumentException("Rail model repetition count is too large");
        count = (long)Math.ceil(length / period);
        origin = new TrackVector((double)chunkX * 16, Math.floor(geometry.sample(0).position().y() / 16) * 16, (double)chunkZ * 16);
    }

    public static RailTrackMeshData build(TrackGeometry geometry, List<Source> sources, int chunkX, int chunkZ,
                                          double scale, double referencePeriod, Filter filter) {
        var builder = new RailTrackMeshBuilder(geometry, chunkX, chunkZ, scale, referencePeriod);
        var modules = new LongArrayList();
        if (!sources.isEmpty()) builder.collectModules(0, builder.count, modules);
        var materials = new LinkedHashMap<MaterialKey, MaterialBuffer>();
        // Keep source/module invocation order even when nonadjacent sources share an output material.
        for (var source : sources) {
            var key = new MaterialKey(source.texture(), source.doubleSided());
            var mesh = source.mesh();
            int[] emittedIndices = null;
            long[] emittedModules = null;
            for (int i = 0; i < modules.size(); i++) {
                long module = modules.getLong(i);
                if (!filter.shouldRender(source.part(), module, builder.count)) continue;
                if (mesh.indices().length == 0) continue;
                if (emittedIndices == null) {
                    emittedIndices = new int[mesh.colors().length];
                    emittedModules = new long[mesh.colors().length];
                    Arrays.fill(emittedModules, -1L);
                }
                var output = materials.computeIfAbsent(key, ignored -> new MaterialBuffer());
                double center = module * builder.period + builder.period / 2;
                for (int index : mesh.indices()) {
                    if (++builder.vertexCount > MAX_VERTICES) {
                        throw new IllegalArgumentException("Rail chunk mesh exceeds 262,144 vertices");
                    }
                    if (emittedModules[index] != module) {
                        emittedIndices[index] = output.colors.size();
                        builder.emit(mesh, index, center, output);
                        emittedModules[index] = module;
                    }
                    output.indices.add(emittedIndices[index]);
                }
            }
        }
        var batches = new ArrayList<RailTrackMeshData.Batch>();
        materials.forEach((key, output) -> batches.add(new RailTrackMeshData.Batch(key.texture(), key.doubleSided(),
                new IndexedMesh(output.vertices.toFloatArray(), output.colors.toIntArray(),
                        output.indices.toIntArray()))));
        var bounds = batches.isEmpty() ? new TrackMeshData.Bounds(0, 0, 0, 0, 0, 0)
                : new TrackMeshData.Bounds(builder.minX, builder.minY, builder.minZ, builder.maxX, builder.maxY, builder.maxZ);
        return new RailTrackMeshData(builder.origin, bounds, batches);
    }

    private void collectModules(long first, long end, LongArrayList modules) {
        double a = first * period, b = Math.min(length, end * period);
        TrackVector middle = geometry.sample(a + (b - a) / 2).position();
        double radius = (b - a) / 2 + 0.001, x = (double)chunkX * 16, z = (double)chunkZ * 16;
        if (middle.x() + radius < x || middle.x() - radius >= x + 16
                || middle.z() + radius < z || middle.z() - radius >= z + 16) return;
        if (end - first > 1) {
            long split = first + (end - first) / 2;
            collectModules(first, split, modules);
            collectModules(split, end, modules);
            return;
        }
        // Own an entire repeated module by its within-track interval midpoint, never by individual faces.
        if (Math.floor(middle.x() / 16) != chunkX || Math.floor(middle.z() / 16) != chunkZ) return;
        modules.add(first);
    }

    private void emit(IndexedMesh mesh, int index, double center, MaterialBuffer output) {
        float[] v = mesh.vertices();
        int offset = index * 8;
        double distance = v[offset + 2] * scale + center;
        double sampledDistance = Math.clamp(distance, 0, length);
        long key = Double.doubleToLongBits(sampledDistance);
        var pose = poses.get(key);
        if (pose == null) {
            pose = geometry.sample(sampledDistance);
            poses.put(key, pose);
        }
        var p = pose.position(); var l = pose.lateral(); var n = pose.normal(); var t = pose.tangent();
        // Preserve overhanging geometry by extending the centerline along its endpoint tangent.
        double extension = distance - sampledDistance;
        double x = v[offset] * scale, y = v[offset + 1] * scale;
        double xw = p.x() + l.x() * x + n.x() * y + t.x() * extension;
        double yw = p.y() + l.y() * x + n.y() * y + t.y() * extension;
        double zw = p.z() + l.z() * x + n.z() * y + t.z() * extension;
        double nx = l.x() * v[offset + 5] + n.x() * v[offset + 6] + t.x() * v[offset + 7];
        double ny = l.y() * v[offset + 5] + n.y() * v[offset + 6] + t.y() * v[offset + 7];
        double nz = l.z() * v[offset + 5] + n.z() * v[offset + 6] + t.z() * v[offset + 7];
        double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (normalLength > 0) { nx /= normalLength; ny /= normalLength; nz /= normalLength; }
        append(output.vertices, xw - origin.x());
        append(output.vertices, yw - origin.y());
        append(output.vertices, zw - origin.z());
        append(output.vertices, v[offset + 3]);
        append(output.vertices, v[offset + 4]);
        append(output.vertices, nx);
        append(output.vertices, ny);
        append(output.vertices, nz);
        output.colors.add(mesh.colors()[index]);
        minX = Math.min(minX, xw); maxX = Math.max(maxX, xw);
        minY = Math.min(minY, yw); maxY = Math.max(maxY, yw);
        minZ = Math.min(minZ, zw); maxZ = Math.max(maxZ, zw);
    }

    private static void append(FloatArrayList vertices, double value) {
        float component = (float)value;
        if (!Float.isFinite(component)) throw new IllegalArgumentException("Non-finite rail vertex");
        vertices.add(component);
    }
}
