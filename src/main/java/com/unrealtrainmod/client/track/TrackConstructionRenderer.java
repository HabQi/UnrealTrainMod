/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import static com.unrealtrainmod.track.mesh.TrackMeshBuilder.RAIL_HEIGHT;
import static com.unrealtrainmod.track.mesh.TrackMeshBuilder.RAIL_WIDTH;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Phase;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** A small immediate line pass; no persistent mesh, GPU buffer, or formal track cache entry. */
public final class TrackConstructionRenderer {
    private TrackConstructionRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        var state = ClientTrackConstruction.INSTANCE;
        if (!state.isActive()) return;
        var draft = state.draft();
        boolean invalid = draft.end() != null && draft.phase() != Phase.READY;
        float red = invalid ? 1 : 0.2f, green = invalid ? 0.2f : 1;
        var previousShader = RenderSystem.getShader();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try (var bytes = new ByteBufferBuilder(4096)) {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var buffers = MultiBufferSource.immediate(bytes);
            var lines = buffers.getBuffer(TrackRenderTypes.PREVIEW);
            Vec3 camera = event.getCamera().getPosition();
            marker(lines, draft.start().position(), camera, red, green);
            if (draft.end() != null) {
                marker(lines, draft.end().position(), camera, red, green);
                line(lines, draft.start().position(), draft.end().position(), camera, red, green);
            }
            var geometry = state.geometry();
            if (geometry != null) {
                TrackPose a = geometry.sample(0), b = geometry.sample(geometry.getLength());
                for (int side = -1; side <= 1; side += 2) {
                    double center = side * (draft.gauge() + RAIL_WIDTH) / 2;
                    TrackVector[] start = section(a, center), end = section(b, center);
                    for (int i = 0; i < 4; i++) {
                        line(lines, start[i], end[i], camera, red, green);
                        line(lines, start[i], start[(i + 1) % 4], camera, red, green);
                        line(lines, end[i], end[(i + 1) % 4], camera, red, green);
                    }
                }
            }
            buffers.endBatch(TrackRenderTypes.PREVIEW);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }

    private static TrackVector[] section(TrackPose pose, double center) {
        return new TrackVector[]{corner(pose, center - RAIL_WIDTH / 2, 0), corner(pose, center + RAIL_WIDTH / 2, 0),
                corner(pose, center + RAIL_WIDTH / 2, -RAIL_HEIGHT), corner(pose, center - RAIL_WIDTH / 2, -RAIL_HEIGHT)};
    }

    private static TrackVector corner(TrackPose pose, double lateral, double normal) {
        var p = pose.position();
        var l = pose.lateral();
        var n = pose.normal();
        return new TrackVector(p.x() + l.x() * lateral + n.x() * normal,
                p.y() + l.y() * lateral + n.y() * normal, p.z() + l.z() * lateral + n.z() * normal);
    }

    private static void marker(VertexConsumer lines, TrackVector p, Vec3 camera, float red, float green) {
        double x = p.x() - camera.x, y = p.y() - camera.y, z = p.z() - camera.z;
        LevelRenderer.renderLineBox(new PoseStack(), lines, x - 0.12, y - 0.12, z - 0.12,
                x + 0.12, y + 0.12, z + 0.12, red, green, 0.2f, 1);
    }

    private static void line(VertexConsumer lines, TrackVector a, TrackVector b, Vec3 camera, float red, float green) {
        double x = b.x() - a.x(), y = b.y() - a.y(), z = b.z() - a.z();
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0) return;
        float nx = (float)(x / length), ny = (float)(y / length), nz = (float)(z / length);
        lines.addVertex((float)(a.x() - camera.x), (float)(a.y() - camera.y), (float)(a.z() - camera.z))
                .setColor(red, green, 0.2f, 1).setNormal(nx, ny, nz);
        lines.addVertex((float)(b.x() - camera.x), (float)(b.y() - camera.y), (float)(b.z() - camera.z))
                .setColor(red, green, 0.2f, 1).setNormal(nx, ny, nz);
    }
}
