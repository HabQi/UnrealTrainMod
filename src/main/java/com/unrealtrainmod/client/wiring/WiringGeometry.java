/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.renderer.ModelRenderTypes;
import com.unrealtrainmod.model.IndexedMesh;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** CPU geometry and retained part transforms, shared by normal rendering and Iris entity shaders. */
public final class WiringGeometry {
    public record Material(ResourceLocation texture, boolean translucent, boolean doubleSided, boolean light) {
        public int pass() { return light ? 1 : translucent ? 2 : 0; }
    }
    public record Batch(String part, Material material, IndexedMesh mesh) {}
    public record Draw(Batch batch, Matrix4f pose, Matrix3f normal, int light) {}
    private WiringGeometry() {}

    public static void render(List<Draw> draws, PoseStack pose, MultiBufferSource buffers, int light) {
        var position = new Vector3f(); var normal = new Vector3f();
        for (var draw : draws) {
            var batch = draw.batch; var material = batch.material;
            var target = buffers.getBuffer(ModelRenderTypes.get(material.texture, material.doubleSided, material.translucent, false));
            var transform = new Matrix4f(pose.last().pose()).mul(draw.pose);
            var normals = new Matrix3f(pose.last().normal()).mul(draw.normal);
            var vertices = batch.mesh.vertices(); var colors = batch.mesh.colors();
            int packedLight = material.light ? 0xF000F0 : draw.light >= 0 ? draw.light : light;
            for (int index : batch.mesh.indices()) {
                int i = index * 8;
                position.set(vertices[i], vertices[i + 1], vertices[i + 2]).mulPosition(transform);
                normal.set(vertices[i + 5], vertices[i + 6], vertices[i + 7]).mul(normals).normalize();
                target.addVertex(position.x, position.y, position.z).setColor(colors[index])
                        .setUv(vertices[i + 3], vertices[i + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(packedLight).setNormal(normal.x, normal.y, normal.z);
            }
        }
    }
    public static List<Draw> identity(List<Batch> batches) {
        return batches.stream().map(b -> new Draw(b, new Matrix4f(), new Matrix3f(), -1)).toList();
    }
}
