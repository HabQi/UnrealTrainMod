/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.BladeContact;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout.RailBend;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.TreeSet;

/** Source preparation only. Curves are local polynomials, never per-vertex path samples. */
final class NativeTurnoutCurves {
    private static final double HEAD_HALF = 0.0354982;
    private NativeTurnoutCurves() {}

    private static void normal(float[] data, int i, double x, double y, double z) {
        double length = Math.sqrt(x*x + y*y + z*z);
        if (length > 0) { data[i + 5] = (float)(x / length); data[i + 6] = (float)(y / length); data[i + 7] = (float)(z / length); }
    }

    static IndexedMesh blade(IndexedMesh source, IndexedMesh rail, float length, BladeContact contact) {
        var vertices = source.vertices().clone(); double factor = length / (0.65 + 1.572079);
        for (int i = 0; i < vertices.length; i += 8) {
            vertices[i + 2] = (float)(0.65 + (vertices[i + 2] - 0.65) * factor);
            normal(vertices, i, vertices[i + 5], vertices[i + 6], vertices[i + 7] / factor);
        }
        var stretched = new IndexedMesh(vertices, source.colors(), source.indices());
        var profile = new TreeMap<Float, Float>(); var template = rail.vertices();
        for (int i = 0; i < template.length; i += 8) if (Math.abs(template[i + 7]) < 0.1f) profile.put(template[i + 4], template[i]);
        double sign = Math.signum(contact.edges().getLast().offset()), center = sign * 0.74875;
        var cuts = new TreeSet<Float>(); cuts.add(0.65f - length); cuts.add(0.65f);
        for (var edge : contact.edges()) cuts.add(Math.clamp(0.65f - length + edge.distance(), 0.65f - length, 0.65f));
        for (int i = 0; i < 4; i++) cuts.add(0.25f + i * 0.1f);
        var planes = new ArrayList<>(cuts); var sections = new ArrayList<IndexedMesh>();
        for (int section = 1; section < planes.size(); section++) {
            var band = NativeTurnoutMeshes.trim(stretched, planes.get(section - 1), planes.get(section));
            var data = band.vertices();
            for (int i = 0; i < data.length; i += 8) {
                float v = Math.clamp(data[i + 4], profile.firstKey(), profile.lastKey());
                var low = profile.floorEntry(v); var high = profile.ceilingEntry(v);
                double target = low.getValue();
                if (!low.getKey().equals(high.getKey())) target += (high.getValue() - target) * (v - low.getKey()) / (high.getKey() - low.getKey());
                double t = Math.clamp((data[i + 2] - 0.25) / 0.4, 0, 1), weight = t*t*(3 - 2*t);
                double delta = target - data[i], x = data[i + 5], y = data[i + 6];
                double z = (1 - weight) * data[i + 7] - x * 6*t*(1 - t) / 0.4 * delta;
                data[i] += (float)(weight * delta); normal(data, i, x, y, z);
                double ratio = (target - center) / (sign * HEAD_HALF);
                if (ratio <= 0 || data[i + 1] <= -0.06) continue;
                double distance = data[i + 2] - (0.65f - length);
                int index = 1;
                while (index < contact.edges().size() - 1 && contact.edges().get(index).distance() < distance) index++;
                var a = contact.edges().get(index - 1); var b = contact.edges().get(index);
                double slope = (b.offset() - a.offset()) / (b.distance() - a.distance());
                double edge = a.offset() + (distance - a.distance()) * slope;
                double width = edge / (sign * HEAD_HALF), derivative = slope / (sign * HEAD_HALF);
                // A contact edge becomes a full rail without a shoulder or excess width.
                if (width >= 1.2) { width = 1; derivative = 0; }
                else if (width > 0.8) {
                    double u = (width - 0.8) / 0.4;
                    width = 0.8 + 0.4*u - 0.2*u*u; derivative *= 1 - u;
                }
                width = Math.max(0, width);
                double blend = Math.clamp((data[i + 1] + 0.06) / 0.035, 0, 1); blend *= blend * (3 - 2*blend);
                double fitted = center + (target - center) * width;
                data[i] += (float)(blend * (fitted - data[i]));
                double nx = data[i + 5], ny = data[i + 6], nz = data[i + 7];
                double fittedX = nx / Math.max(width, 0.0001);
                double fittedZ = -fittedX * (target - center) * derivative;
                normal(data, i, nx*(1 - blend) + fittedX*blend, ny, nz*(1 - blend) + fittedZ*blend);
            }
            sections.add(band);
        }
        return NativeTurnoutMeshes.join(sections);
    }

    static IndexedMesh rail(IndexedMesh source, float length, RailBend bend, double center) {
        if (bend.start() == 0 && bend.end() == 0) return NativeTurnoutMeshes.trim(source, -length / 2 - 0.0005f, length / 2 + 0.0005f);
        double startSlope = Math.tan(bend.start()), endSlope = Math.tan(bend.end());
        int count = Math.max(1, (int)Math.ceil(length / 0.1));
        var sections = new ArrayList<IndexedMesh>();
        for (int section = 0; section < count; section++) {
            float start = section == 0 ? -length / 2 - 0.0005f : -length / 2 + length * section / count;
            float end = section == count - 1 ? length / 2 + 0.0005f : -length / 2 + length * (section + 1) / count;
            var band = NativeTurnoutMeshes.trim(source, start, end); var data = band.vertices();
            for (int i = 0; i < data.length; i += 8) {
                double t = (data[i + 2] + length / 2) / length;
                double offset = length * ((t*t*t - 2*t*t + t)*startSlope + (t*t*t - t*t)*endSlope);
                double slope = (3*t*t - 4*t + 1)*startSlope + (3*t*t - 2*t)*endSlope;
                double cosine = 1 / Math.sqrt(1 + slope*slope), sine = slope*cosine;
                double across = data[i] - center;
                data[i] = (float)(center + offset + across*cosine);
                data[i + 2] -= (float)(across*sine);
                normal(data, i, data[i + 5]*cosine + data[i + 7]*sine, data[i + 6], -data[i + 5]*sine + data[i + 7]*cosine);
            }
            sections.add(band);
        }
        return NativeTurnoutMeshes.join(sections);
    }
}
