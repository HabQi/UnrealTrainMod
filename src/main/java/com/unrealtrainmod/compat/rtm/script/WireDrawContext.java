/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm.script;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.wiring.WiringGeometry;
import com.unrealtrainmod.client.wiring.WiringModelManager;
import com.unrealtrainmod.client.wiring.WireShape;
import com.unrealtrainmod.model.IndexedMesh;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Transactional part and immediate-quad output for a single wire callback. */
final class WireDrawContext extends ScriptMatrixContext {
    private final WiringModelManager.Model model;
    private final int pass;
    private final List<WiringGeometry.Draw> output = new ArrayList<>();
    private final List<float[]> quadVertices = new ArrayList<>();
    private boolean drawing;
    private int light = -1;
    private boolean directionalLighting = true;
    WireDrawContext(PoseStack pose, WiringModelManager.Model model, int pass) { super(pose); this.model = model; this.pass = pass; }
    void parts(List<String> names) {
        var selected = model.batches().stream().filter(b -> names.stream().anyMatch(n -> n.strip().equalsIgnoreCase(b.part().strip()))).toList();
        for (var batch : selected) if (batch.material().pass() == pass) output.add(new WiringGeometry.Draw(batch,
                new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), drawLight()));
    }
    void repeat(Vec3 target, boolean sag, Parts parts, WireShape.Filter filter) {
        var selected = model.batches().stream().filter(b -> b.material().pass() == pass && (parts == null
                || parts.names().stream().anyMatch(n -> n.strip().equalsIgnoreCase(b.part().strip())))).toList();
        output.addAll(WireShape.build(model.definition(), selected, target, sag, pose, drawLight(), filter));
    }
    void begin() {
        if (drawing) throw new IllegalStateException("Nested NGTTessellator drawing");
        quadVertices.clear(); drawing = true;
    }
    void vertex(double x, double y, double z, double u, double v) {
        if (!drawing) throw new IllegalStateException("NGTTessellator vertex outside startDrawingQuads");
        double[] values = {x, y, z, u, v};
        for (double value : values) if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
            throw new IllegalArgumentException("Non-finite tessellator vertex");
        }
        if (quadVertices.size() >= 262144) throw new IllegalArgumentException("Wire immediate vertex limit exceeded");
        quadVertices.add(new float[]{(float)x, (float)y, (float)z, (float)u, (float)v});
    }
    int end() {
        if (!drawing || quadVertices.size() % 4 != 0) throw new IllegalStateException("Incomplete tessellator quads");
        drawing = false; int count = quadVertices.size();
        if (count == 0 || model.firstTexture().pass() != pass) { quadVertices.clear(); return count; }
        var vertices = new float[count * 8]; var colors = new int[count]; var indices = new int[count / 4 * 6];
        for (int start = 0; start < count; start += 4) {
            var a = quadVertices.get(start); var b = quadVertices.get(start + 1); var c = quadVertices.get(start + 2);
            var normal = new Vector3f(b[0] - a[0], b[1] - a[1], b[2] - a[2])
                    .cross(c[0] - a[0], c[1] - a[1], c[2] - a[2]);
            if (normal.lengthSquared() > 1e-12f) normal.normalize(); else normal.set(0, 1, 0);
            for (int corner = 0; corner < 4; corner++) {
                int index = start + corner, offset = index * 8; var v = quadVertices.get(index);
                System.arraycopy(v, 0, vertices, offset, 5);
                vertices[offset + 5] = normal.x; vertices[offset + 6] = normal.y; vertices[offset + 7] = normal.z;
                colors[index] = 0xFFFFFFFF;
            }
            int index = start / 4 * 6;
            int[] order = {0, 1, 2, 0, 2, 3}; for (int i = 0; i < 6; i++) indices[index + i] = start + order[i];
        }
        var batch = new WiringGeometry.Batch("", model.firstTexture(), new IndexedMesh(vertices, colors, indices));
        output.add(new WiringGeometry.Draw(batch, new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), drawLight()));
        quadVertices.clear(); return count;
    }
    List<WiringGeometry.Draw> finish() {
        if (drawing) throw new IllegalStateException("Missing NGTTessellator.draw");
        return List.copyOf(output);
    }
    @Override void brightness(int value) { light = value; }
    private int drawLight() { return directionalLighting ? light : 0xF000F0; }
    @Override void directionalLighting(boolean value) { directionalLighting = value; }
    @Override void discard() { output.clear(); quadVertices.clear(); drawing = false; }
}
