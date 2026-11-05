/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;

/** Native track quads become real lit surfaces; no pre-baked directional shading is retained. */
final class TrackIrisMesh {
    private static final int[] CORNERS = {0, 1, 2, 0, 2, 3};
    private TrackIrisMesh() {}

    static int upload(VertexBuffer buffer, TrackMeshData normal, TrackMeshData reverse, float movement,
                      TrackLighting lighting, ClientLevel level) {
        int count = normal.vertexCount() / 4 * 6;
        try (var bytes = new ByteBufferBuilder(Math.max(256, count * DefaultVertexFormat.NEW_ENTITY.getVertexSize()))) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            var points = new Vector3f[] {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
            var a = new Vector3f(); var b = new Vector3f();
            for (int face = 0; face < normal.vertexCount(); face += 4) {
                for (int corner = 0; corner < 4; corner++) {
                    int i = face + corner;
                    var p = points[corner].set(normal.x(i), normal.y(i), normal.z(i));
                    if (reverse != null) p.lerp(b.set(
                            (float)(reverse.x(i) + reverse.origin().x() - normal.origin().x()),
                            (float)(reverse.y(i) + reverse.origin().y() - normal.origin().y()),
                            (float)(reverse.z(i) + reverse.origin().z() - normal.origin().z())), movement);
                }
                points[1].sub(points[0], a);
                points[2].sub(points[0], b);
                a.cross(b).normalize();
                for (int corner : CORNERS) {
                    var p = points[corner];
                    int light = lighting.sample(level, p.x, p.y, p.z, a.x, a.y, a.z);
                    builder.addVertex(p.x, p.y, p.z).setColor(normal.baseColor(face + corner))
                            .setUv(corner == 1 || corner == 2 ? 1 : 0, corner >= 2 ? 1 : 0)
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(a.x, a.y, a.z);
                }
            }
            buffer.bind();
            var mesh = builder.buildOrThrow();
            int size = mesh.vertexBuffer().remaining();
            buffer.upload(mesh);
            return size;
        } finally { VertexBuffer.unbind(); }
    }
}
