/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.track.mesh.NativeDiamondLayout;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/** Resource-time extraction of complete connected components; source vertex attributes stay intact. */
public final class NativeDiamondModels {
    public static final String RECIPE = "native-diamond-r11-center-out-25";
    private NativeDiamondModels() {}
    private record Point(long x, long y, long z) {}
    private record Component(IndexedMesh mesh, double minX, double maxX) {
        boolean guard() { return Math.abs((minX+maxX)/2) > 0.8; }
    }

    static ClientRailModelManager.Model prepare(ClientRailModelManager.Model original) {
        var parts = new ArrayList<>(original.parts());
        for (String side : List.of("L", "R")) {
            var front = components(original, "wing"+side+"f");
            parts.add(select("wingFront"+side, front, component -> !component.guard()));
            parts.add(select("guard"+side, front, Component::guard));
        }
        var data = NativeDiamondRenderState.build(NativeDiamondLayout.example(), NativeTurnoutModels.sources(parts), null);
        var b = data.bounds();
        var bounds = new ModelBounds((float)b.minX(), (float)b.minY(), (float)b.minZ(),
                (float)b.maxX(), (float)b.maxY(), (float)b.maxZ());
        var batches = data.batches().stream().map(batch ->
                new ClientRailModelManager.Batch(batch.mesh(), batch.texture(), batch.doubleSided())).toList();
        return new ClientRailModelManager.Model(batches, parts, original.padding(), bounds,
                original.format(), original.period(), false, null);
    }

    private record MaterialComponents(ClientRailModelManager.Batch batch, List<Component> components) {}
    private static List<MaterialComponents> components(ClientRailModelManager.Model model, String name) {
        var part = model.parts().stream().filter(value -> value.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing native r11 part: "+name));
        return part.batches().stream().map(batch -> new MaterialComponents(batch, components(batch.mesh()))).toList();
    }
    private static ClientRailModelManager.Part select(String name, List<MaterialComponents> groups, Predicate<Component> predicate) {
        var batches = new ArrayList<ClientRailModelManager.Batch>();
        for (var group : groups) {
            var indices = new IntArrayList();
            for (var component : group.components()) if (predicate.test(component)) indices.addElements(indices.size(), component.mesh().indices());
            if (indices.isEmpty()) continue;
            var source = group.batch();
            batches.add(new ClientRailModelManager.Batch(new IndexedMesh(source.mesh().vertices(), source.mesh().colors(), indices.toIntArray()),
                    source.texture(), source.doubleSided()));
        }
        if (batches.isEmpty()) throw new IllegalArgumentException("No complete native r11 components for "+name);
        return new ClientRailModelManager.Part(name, batches);
    }

    private static List<Component> components(IndexedMesh mesh) {
        int[] parent = new int[mesh.colors().length];
        var positions = new HashMap<Point, Integer>();
        var vertices = mesh.vertices();
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
            int v = i*8;
            var key = new Point(Math.round(vertices[v]*1_000_000.0), Math.round(vertices[v+1]*1_000_000.0),
                    Math.round(vertices[v+2]*1_000_000.0));
            var previous = positions.putIfAbsent(key, i);
            if (previous != null) union(parent, i, previous);
        }
        var indices = mesh.indices();
        for (int i = 0; i < indices.length; i += 3) {
            union(parent, indices[i], indices[i+1]); union(parent, indices[i], indices[i+2]);
        }
        var groups = new LinkedHashMap<Integer, IntArrayList>();
        for (int i = 0; i < indices.length; i += 3) {
            var group = groups.computeIfAbsent(root(parent, indices[i]), ignored -> new IntArrayList());
            group.add(indices[i]); group.add(indices[i+1]); group.add(indices[i+2]);
        }
        var result = new ArrayList<Component>();
        for (var group : groups.values()) {
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < group.size(); i++) {
                int v = group.getInt(i)*8;
                minX = Math.min(minX, vertices[v]); maxX = Math.max(maxX, vertices[v]);
            }
            result.add(new Component(new IndexedMesh(vertices, mesh.colors(), group.toIntArray()), minX, maxX));
        }
        return result;
    }
    private static int root(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }
    private static void union(int[] parent, int a, int b) { parent[root(parent, b)] = root(parent, a); }
}
