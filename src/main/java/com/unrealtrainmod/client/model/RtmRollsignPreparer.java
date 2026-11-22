/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.compat.rtm.RtmRollsign;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelData.Position;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import org.joml.Vector3f;

/** Bakes the fixed variants of an RTM direction display during vehicle resource preparation. */
final class RtmRollsignPreparer {
    private static final int[] TRIANGLES = {3, 2, 1, 3, 1, 0};
    private static final PartSelection EMPTY = new PartSelection(List.of(), List.of());

    private RtmRollsignPreparer() {}

    static List<PreparedModel.RollsignFrame> prepare(RtmRollsign sign, TexturePool textures) throws IOException {
        var texture = textures.load(sign.texture());
        var lit = new ArrayList<RtmRollsign.Quad>();
        var emissive = new ArrayList<RtmRollsign.Quad>();
        for (var quad : sign.quads()) (quad.fullBright() ? emissive : lit).add(quad);
        int rows = sign.names().size();
        var frames = new ArrayList<PreparedModel.RollsignFrame>(rows);
        for (int index = 0; index < rows; index++) {
            frames.add(new PreparedModel.RollsignFrame(bake(lit, texture, index, rows),
                    bake(emissive, texture, index, rows)));
        }
        return List.copyOf(frames);
    }

    private static PartSelection bake(List<RtmRollsign.Quad> quads, TexturePool.Entry texture,
                                      int frame, int rows) throws IOException {
        if (quads.isEmpty()) return EMPTY;
        var vertices = new float[quads.size() * 4 * 8];
        var colors = new int[quads.size() * 4];
        Arrays.fill(colors, 0xffffffff);
        var groups = new EnumMap<RenderCategory, IntArrayList>(RenderCategory.class);
        for (int i = 0; i < quads.size(); i++) {
            var quad = quads.get(i);
            var corners = quad.corners();
            var origin = vector(corners.get(3));
            var normal = vector(corners.get(2)).sub(origin).cross(vector(corners.get(1)).sub(origin));
            if (!normal.isFinite() || normal.lengthSquared() == 0) throw new IOException("Degenerate rollsign surface");
            normal.normalize();
            float band = (quad.v1() - quad.v0()) / rows;
            float top = quad.v0() + band * frame;
            float bottom = quad.v0() + band * (frame + 1);
            int first = i * 4;
            for (int corner = 0; corner < 4; corner++) {
                var point = corners.get(corner);
                int offset = (first + corner) * 8;
                vertices[offset] = point.x();
                vertices[offset + 1] = point.y();
                vertices[offset + 2] = point.z();
                vertices[offset + 3] = corner < 2 ? quad.u1() : quad.u0();
                vertices[offset + 4] = corner == 0 || corner == 3 ? top : bottom;
                vertices[offset + 5] = normal.x;
                vertices[offset + 6] = normal.y;
                vertices[offset + 7] = normal.z;
            }
            for (int triangle = 0; triangle < TRIANGLES.length; triangle += 3) {
                int a = first + TRIANGLES[triangle], b = first + TRIANGLES[triangle + 1], c = first + TRIANGLES[triangle + 2];
                var category = texture.alpha().classify(vertices, colors, a, b, c, true, null);
                if (category == null) continue;
                var indices = groups.computeIfAbsent(category, ignored -> new IntArrayList());
                indices.add(a);
                indices.add(b);
                indices.add(c);
            }
        }
        var batches = new ArrayList<RenderBatch>(groups.size());
        groups.forEach((category, indices) -> batches.add(new RenderBatch(
                new IndexedMesh(vertices, colors, indices.toIntArray()), texture.id(), category, null, false)));
        return new PartSelection(List.of(), batches);
    }

    private static Vector3f vector(Position point) { return new Vector3f(point.x(), point.y(), point.z()); }
}
