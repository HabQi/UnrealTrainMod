/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.client.track.NativeTurnoutMeshes.Corner;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

/** Longitudinal subdivision only: no overlap detection, surface removal or cut caps. */
final class NativeRailSlices {
    private NativeRailSlices() {}
    private record Node(Corner corner, int a, int b) { float z() { return corner.values()[2]; } }
    private record Crossing(int a, int b, float plane) {}
    private static final class Band {
        final FloatArrayList vertices = new FloatArrayList();
        final IntArrayList colors = new IntArrayList(), indices = new IntArrayList();
        final IdentityHashMap<Node, Integer> mapped = new IdentityHashMap<>();
        void polygon(Node[] nodes, int count) {
            if (count < 3) return;
            int first = index(nodes[0]), before = index(nodes[1]);
            for (int i = 2; i < count; i++) {
                int next = index(nodes[i]); indices.add(first); indices.add(before); indices.add(next); before = next;
            }
        }
        int index(Node node) {
            var old = mapped.get(node); if (old != null) return old;
            int index = colors.size(); mapped.put(node, index); colors.add(node.corner().color());
            for (float value : node.corner().values()) vertices.add(value);
            return index;
        }
        IndexedMesh finish() { return new IndexedMesh(vertices.toFloatArray(), colors.toIntArray(), indices.toIntArray()); }
    }
    static List<IndexedMesh> split(IndexedMesh mesh, List<Float> cuts) {
        float[] planes = new float[cuts.size()];
        for (int i = 0; i < planes.length; i++) planes[i] = cuts.get(i);
        var bands = new Band[planes.length-1];
        for (int i = 0; i < bands.length; i++) bands[i] = new Band();
        var originals = new Node[mesh.colors().length];
        var crossings = new HashMap<Crossing, Node>();
        var triangle = new Node[3]; var low = new Node[6]; var high = new Node[6];
        for (int offset = 0; offset < mesh.indices().length; offset += 3) {
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 3; i++) {
                int index = mesh.indices()[offset+i];
                if (originals[index] == null) originals[index] = new Node(new Corner(
                        java.util.Arrays.copyOfRange(mesh.vertices(), index*8, index*8+8), mesh.colors()[index]), index, index);
                triangle[i] = originals[index]; min = Math.min(min, triangle[i].z()); max = Math.max(max, triangle[i].z());
            }
            int first = Math.max(0, bound(planes, min, false)-1), last = Math.min(bands.length-1, bound(planes, max, true)-1);
            for (int i = first; i <= last; i++) {
                int count = clip(triangle, 3, low, planes[i], true, originals, crossings);
                count = clip(low, count, high, planes[i+1], false, originals, crossings);
                bands[i].polygon(high, count);
            }
        }
        var result = new ArrayList<IndexedMesh>(bands.length);
        for (var band : bands) result.add(band.finish());
        return result;
    }
    private static int bound(float[] planes, float value, boolean upper) {
        int low = 0, high = planes.length;
        while (low < high) {
            int mid = (low+high)/2;
            if (planes[mid] < value || upper && planes[mid] == value) low = mid+1; else high = mid;
        }
        return low;
    }
    private static int clip(Node[] input, int count, Node[] output, float plane, boolean above,
                            Node[] originals, HashMap<Crossing, Node> crossings) {
        if (count == 0) return 0;
        int size = 0; var previous = input[count-1];
        boolean previousInside = above ? previous.z() >= plane : previous.z() <= plane;
        for (int i = 0; i < count; i++) {
            var current = input[i]; boolean inside = above ? current.z() >= plane : current.z() <= plane;
            if (inside != previousInside) {
                int a, b;
                if (previous.a() != previous.b()) { a = previous.a(); b = previous.b(); }
                else if (current.a() != current.b()) { a = current.a(); b = current.b(); }
                else { a = previous.a(); b = current.a(); }
                var key = new Crossing(Math.min(a,b), Math.max(a,b), plane);
                var intersection = crossings.get(key);
                if (intersection == null) {
                    var start = originals[key.a()]; var end = originals[key.b()];
                    var value = NativeTurnoutMeshes.interpolate(start.corner(), end.corner(), (plane-start.z())/(end.z()-start.z()));
                    value.values()[2] = plane;
                    intersection = new Node(value, key.a(), key.b()); crossings.put(key, intersection);
                }
                output[size++] = intersection;
            }
            if (inside) output[size++] = current;
            previous = current; previousInside = inside;
        }
        return size;
    }
}
