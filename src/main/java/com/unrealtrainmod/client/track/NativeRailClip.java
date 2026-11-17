/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.client.track.NativeTurnoutMeshes.Corner;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;

/** Length, joint and chunk boundary clipping; never subdivides the retained interval or adds caps. */
final class NativeRailClip {
    private NativeRailClip() {}
    private record Node(Corner corner, int a, int b) { float coordinate(int axis) { return corner.values()[axis]; } }
    private record Crossing(int a, int b, float plane) {}
    record Section(IndexedMesh mesh, float[] parameters) {}
    private static final class Output {
        final FloatArrayList vertices = new FloatArrayList();
        final IntArrayList colors = new IntArrayList(), indices = new IntArrayList();
        final IdentityHashMap<Node, Integer> mapped = new IdentityHashMap<>();
        final FloatArrayList parameters;
        Output(boolean withParameters) { parameters = withParameters ? new FloatArrayList() : null; }
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
            var values = node.corner().values();
            for (int i = 0; i < 8; i++) vertices.add(values[i]);
            if (parameters != null) parameters.add(values[8]);
            return index;
        }
        Section finish() { return new Section(new IndexedMesh(vertices.toFloatArray(), colors.toIntArray(), indices.toIntArray()),
                parameters == null ? null : parameters.toFloatArray()); }
    }
    static IndexedMesh clip(IndexedMesh mesh, float start, float end) {
        return clip(mesh, start, end, null).mesh();
    }
    static Section clip(IndexedMesh mesh, float start, float end, float[] parameters) {
        return clip(mesh,2,start,end,parameters);
    }
    static IndexedMesh clipAxis(IndexedMesh mesh, int axis, float start, float end) {
        return clip(mesh,axis,start,end,null).mesh();
    }
    private static Section clip(IndexedMesh mesh, int axis, float start, float end, float[] parameters) {
        var result = new Output(parameters != null);
        var originals = new Node[mesh.colors().length];
        var crossings = new HashMap<Crossing, Node>();
        var triangle = new Node[3]; var low = new Node[6]; var high = new Node[6];
        for (int offset = 0; offset < mesh.indices().length; offset += 3) {
            for (int i = 0; i < 3; i++) {
                int index = mesh.indices()[offset+i];
                if (originals[index] == null) {
                    float[] values = new float[parameters == null ? 8 : 9];
                    System.arraycopy(mesh.vertices(), index*8, values, 0, 8);
                    if (parameters != null) values[8] = parameters[index];
                    originals[index] = new Node(new Corner(values, mesh.colors()[index]), index, index);
                }
                triangle[i] = originals[index];
            }
            int count = clip(triangle, 3, low, axis, start, true, originals, crossings);
            count = clip(low, count, high, axis, end, false, originals, crossings);
            result.polygon(high, count);
        }
        return result.finish();
    }
    private static int clip(Node[] input, int count, Node[] output, int axis, float plane, boolean above,
                            Node[] originals, HashMap<Crossing, Node> crossings) {
        if (count == 0) return 0;
        int size = 0; var previous = input[count-1];
        boolean previousInside = above ? previous.coordinate(axis) >= plane : previous.coordinate(axis) <= plane;
        for (int i = 0; i < count; i++) {
            var current = input[i]; boolean inside = above ? current.coordinate(axis) >= plane : current.coordinate(axis) <= plane;
            if (inside != previousInside && previous.coordinate(axis) != plane && current.coordinate(axis) != plane) {
                int a, b;
                if (previous.a() != previous.b()) { a = previous.a(); b = previous.b(); }
                else if (current.a() != current.b()) { a = current.a(); b = current.b(); }
                else { a = previous.a(); b = current.a(); }
                var key = new Crossing(Math.min(a,b), Math.max(a,b), plane);
                var intersection = crossings.get(key);
                if (intersection == null) {
                    var start = originals[key.a()]; var end = originals[key.b()];
                    var value = NativeTurnoutMeshes.interpolate(start.corner(), end.corner(),
                            (plane-start.coordinate(axis))/(end.coordinate(axis)-start.coordinate(axis)));
                    value.values()[axis] = plane;
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
