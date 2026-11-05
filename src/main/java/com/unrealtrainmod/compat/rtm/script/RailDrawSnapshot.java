/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Local-to-anchor transforms and immutable model references, never expanded world vertices. */
public record RailDrawSnapshot(List<Part> parts) {
    public RailDrawSnapshot { parts = List.copyOf(parts); }

    public static final class Part {
        private final Source source;
        private final Matrix4f transform;
        private final Vector3f position, scale;
        private final Quaternionf rotation;

        public Part(Source source, Matrix4f matrix) {
            if (!matrix.isFinite()) throw new IllegalArgumentException("Non-finite rail script transform");
            this.source = source;
            transform = new Matrix4f(matrix);
            position = matrix.getTranslation(new Vector3f());
            scale = matrix.getScale(new Vector3f());
            // Preserve a reflected part when decomposing its matrix for interpolation.
            if (matrix.determinant3x3() < 0) scale.x = -scale.x;
            rotation = new Quaternionf();
            if (scale.x != 0 && scale.y != 0 && scale.z != 0) {
                new Matrix4f(matrix).scale(1 / scale.x, 1 / scale.y, 1 / scale.z).getNormalizedRotation(rotation);
            }
        }
        public Source source() { return source; }
        public Matrix4f transform() { return transform; }
    }

    public boolean matches(RailDrawSnapshot other) {
        if (other == null || parts.size() != other.parts.size()) return false;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).source != other.parts.get(i).source) return false;
        }
        return true;
    }

    public Matrix4f interpolate(RailDrawSnapshot previous, int index, float fraction, Matrix4f result) {
        var current = parts.get(index);
        if (previous == null || fraction >= 1) return result.set(current.transform);
        var before = previous.parts.get(index);
        if (fraction <= 0) return result.set(before.transform);
        if (before.transform.equals(current.transform)) return result.set(current.transform);
        var position = new Vector3f(before.position).lerp(current.position, fraction);
        var scale = new Vector3f(before.scale).lerp(current.scale, fraction);
        var rotation = new Quaternionf(before.rotation).slerp(current.rotation, fraction);
        return result.translationRotateScale(position, rotation, scale);
    }
}
