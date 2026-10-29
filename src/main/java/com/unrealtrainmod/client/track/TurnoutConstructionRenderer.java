/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.unrealtrainmod.network.payload.TurnoutResultPayload.Feedback;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Immediate preview, including the exact union footprint of both internal paths. */
public final class TurnoutConstructionRenderer {
    private TurnoutConstructionRenderer() {}
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        var state = ClientTurnoutConstruction.INSTANCE;
        var draft = state.draft(); var geometry = state.geometry();
        if (draft == null || geometry == null) return;
        int color = state.feedback() == Feedback.READY ? 0xFF50E070
                : state.feedback() == null ? 0xFFFFCC55 : 0xFFFF5555;
        var previousShader = RenderSystem.getShader();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        var view = RenderSystem.getModelViewStack();
        view.pushMatrix(); view.set(event.getModelViewMatrix()); RenderSystem.applyModelViewMatrix();
        try (var bytes = new ByteBufferBuilder(4096)) {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var buffers = MultiBufferSource.immediate(bytes);
            var lines = buffers.getBuffer(TrackRenderTypes.PREVIEW);
            var camera = event.getCamera().getPosition();
            path(lines, geometry.straight(), draft.gauge(), camera, color);
            path(lines, geometry.diverging(), draft.gauge(), camera, color);
            var volume = state.volume();
            for (var entry : volume.entrySet()) {
                BlockPos p = entry.getKey();
                double y = p.getY() + entry.getValue() / 16.0 + 0.015;
                var a = new TrackVector(p.getX(), y, p.getZ());
                var b = new TrackVector(p.getX() + 1, y, p.getZ());
                var c = new TrackVector(p.getX() + 1, y, p.getZ() + 1);
                var d = new TrackVector(p.getX(), y, p.getZ() + 1);
                if (!volume.containsKey(p.north())) line(lines, a, b, camera, color);
                if (!volume.containsKey(p.east())) line(lines, b, c, camera, color);
                if (!volume.containsKey(p.south())) line(lines, c, d, camera, color);
                if (!volume.containsKey(p.west())) line(lines, d, a, camera, color);
            }
            double[] directions = {state.yaw() + 180, state.yaw(), state.definition().exitYaw(state.yaw())};
            TrackVector[] ports = {draft.origin().position(), geometry.b(), geometry.c()};
            for (int i = 0; i < ports.length; i++) arrow(lines, ports[i], directions[i], camera, color);
            buffers.endBatch(TrackRenderTypes.PREVIEW);
            var font = Minecraft.getInstance().font;
            for (int i = 0; i < ports.length; i++) {
                var point = ports[i];
                var pose = new PoseStack();
                pose.translate(point.x() - camera.x, point.y() - camera.y + 0.6, point.z() - camera.z);
                pose.mulPose(event.getCamera().rotation()); pose.scale(0.025f, -0.025f, 0.025f);
                double direction = ((directions[i] % 360) + 540) % 360 - 180;
                String label = String.format(Locale.ROOT, "%c  %.1f°", 'A' + i, direction);
                font.drawInBatch(label, -font.width(label) / 2f, 0, color, true, pose.last().pose(),
                        buffers, Font.DisplayMode.NORMAL, 0x80000000, 0xF000F0);
            }
            buffers.endBatch();
        } finally {
            view.popMatrix(); RenderSystem.applyModelViewMatrix(); RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }
    private static void path(VertexConsumer lines, TrackGeometry geometry, double gauge, Vec3 camera, int color) {
        int steps = Math.max(1, (int)Math.ceil(geometry.getLength() / 0.5));
        TrackPose a = geometry.sample(0);
        for (int i = 1; i <= steps; i++) {
            var b = geometry.sample(geometry.getLength() * i / steps);
            line(lines, a.position(), b.position(), camera, color);
            line(lines, offset(a, -gauge / 2), offset(b, -gauge / 2), camera, color);
            line(lines, offset(a, gauge / 2), offset(b, gauge / 2), camera, color);
            a = b;
        }
    }
    private static TrackVector offset(TrackPose pose, double side) {
        return new TrackVector(pose.position().x() + pose.lateral().x() * side, pose.position().y() + 0.015,
                pose.position().z() + pose.lateral().z() * side);
    }
    private static void arrow(VertexConsumer lines, TrackVector p, double yaw, Vec3 camera, int color) {
        double angle = Math.toRadians(yaw), dx = -Math.sin(angle), dz = Math.cos(angle);
        var tip = new TrackVector(p.x() + dx * 2, p.y() + 0.12, p.z() + dz * 2);
        line(lines, new TrackVector(p.x(), p.y() + 0.12, p.z()), tip, camera, color);
        for (int side : new int[]{-1, 1}) line(lines, tip,
                new TrackVector(tip.x() - dx * 0.5 + dz * side * 0.3, tip.y(), tip.z() - dz * 0.5 - dx * side * 0.3),
                camera, color);
    }
    private static void line(VertexConsumer lines, TrackVector a, TrackVector b, Vec3 camera, int color) {
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) return;
        float nx = (float)(dx / length), ny = (float)(dy / length), nz = (float)(dz / length);
        lines.addVertex((float)(a.x() - camera.x), (float)(a.y() - camera.y), (float)(a.z() - camera.z))
                .setColor(color).setNormal(nx, ny, nz);
        lines.addVertex((float)(b.x() - camera.x), (float)(b.y() - camera.y), (float)(b.z() - camera.z))
                .setColor(color).setNormal(nx, ny, nz);
    }
}
