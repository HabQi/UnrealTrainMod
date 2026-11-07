/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.item;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/** Immutable CPU geometry built during model baking, before any render-thread GPU allocation. */
public record ItemGeometry(List<RenderBatch> batches) {
    private record Vertex(int x, int y, int z, int color, int u, int v, int normal) {}

    static ItemGeometry extract(BakedModel model) {
        var random = RandomSource.create(42);
        var quads = new ArrayList<BakedQuad>();
        for (var direction : Direction.values()) {
            random.setSeed(42);
            quads.addAll(model.getQuads(null, direction, random));
        }
        random.setSeed(42);
        quads.addAll(model.getQuads(null, null, random));
        var sprites = new IdentityHashMap<TextureAtlasSprite, Boolean>();
        var vertices = new FloatArrayList();
        var colors = new IntArrayList();
        var unique = new HashMap<Vertex, Integer>();
        var groups = new LinkedHashMap<ResourceLocation, IntArrayList>();
        int stride = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
        for (var quad : quads) {
            if (quad.isTinted() || quad.getVertices().length != stride * 4) return null;
            var sprite = quad.getSprite();
            if (!sprites.computeIfAbsent(sprite, ItemGeometry::staticCutout)) return null;
            var indices = groups.computeIfAbsent(sprite.atlasLocation(), ignored -> new IntArrayList());
            int[] corners = new int[4], data = quad.getVertices();
            for (int corner = 0; corner < 4; corner++) {
                int offset = corner * stride, packed = data[offset + 3];
                if ((packed >>> 24) != 255 || data[offset + 6] != 0) return null;
                int color = (packed & 0xff00ff00) | (packed & 255) << 16 | (packed >>> 16 & 255);
                int normal = data[offset + 7] & 0xffffff;
                if (normal == 0) {
                    var n = quad.getDirection().getNormal();
                    normal = (n.getX() * 127 & 255) | (n.getY() * 127 & 255) << 8 | (n.getZ() * 127 & 255) << 16;
                }
                var key = new Vertex(data[offset], data[offset + 1], data[offset + 2], color,
                        data[offset + 4], data[offset + 5], normal);
                var existing = unique.get(key);
                if (existing == null) {
                    existing = colors.size();
                    unique.put(key, existing);
                    vertices.add(Float.intBitsToFloat(key.x()));
                    vertices.add(Float.intBitsToFloat(key.y()));
                    vertices.add(Float.intBitsToFloat(key.z()));
                    vertices.add(Float.intBitsToFloat(key.u()));
                    vertices.add(Float.intBitsToFloat(key.v()));
                    vertices.add((byte)normal / 127f);
                    vertices.add((byte)(normal >>> 8) / 127f);
                    vertices.add((byte)(normal >>> 16) / 127f);
                    colors.add(color);
                }
                corners[corner] = existing;
            }
            triangle(indices, vertices, corners[0], corners[1], corners[2]);
            triangle(indices, vertices, corners[0], corners[2], corners[3]);
        }
        if (vertices.isEmpty()) return null;
        float[] positions = vertices.toFloatArray();
        int[] rgba = colors.toIntArray();
        var batches = new ArrayList<RenderBatch>();
        groups.forEach((texture, indices) -> {
            if (!indices.isEmpty()) batches.add(new RenderBatch(new IndexedMesh(positions, rgba, indices.toIntArray()),
                    texture, RenderCategory.CUTOUT, MaterialRenderMode.CUTOUT, true));
        });
        return new ItemGeometry(List.copyOf(batches));
    }

    private static boolean staticCutout(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        if (contents.getUniqueFrames().count() > 1) return false;
        var pixels = contents.getOriginalImage();
        for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++) {
            int alpha = pixels.getPixelRGBA(x, y) >>> 24;
            if (alpha != 0 && alpha != 255) return false;
        }
        return true;
    }

    private static void triangle(IntArrayList indices, FloatArrayList vertices, int a, int b, int c) {
        int i = a * 8, j = b * 8, k = c * 8;
        float abx = vertices.getFloat(j) - vertices.getFloat(i), aby = vertices.getFloat(j + 1) - vertices.getFloat(i + 1);
        float abz = vertices.getFloat(j + 2) - vertices.getFloat(i + 2);
        float acx = vertices.getFloat(k) - vertices.getFloat(i), acy = vertices.getFloat(k + 1) - vertices.getFloat(i + 1);
        float acz = vertices.getFloat(k + 2) - vertices.getFloat(i + 2);
        if (aby * acz - abz * acy == 0 && abz * acx - abx * acz == 0 && abx * acy - aby * acx == 0) return;
        indices.add(a); indices.add(b); indices.add(c);
    }
}
