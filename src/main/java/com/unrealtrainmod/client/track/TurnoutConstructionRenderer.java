/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.AssemblyPlacementGeometry;
import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Immediate preview, including the exact union footprint of both internal paths. */
public final class TurnoutConstructionRenderer {
    private static final NativeTurnoutPreview MODEL = new NativeTurnoutPreview(false);
    private static TrackAssemblyDefinition.Geometry modelGeometry;
    private static TrackAssemblyInstance modelAssembly;
    private TurnoutConstructionRenderer() {}

    public static void clearModel() {
        MODEL.clear(); modelGeometry = null; modelAssembly = null;
    }

    public static void renderShadow(RenderLevelStageEvent event) { renderModel(event, true); }

    private static void renderModel(RenderLevelStageEvent event, boolean shadow) {
        var state = ClientTurnoutConstruction.INSTANCE;
        var player = Minecraft.getInstance().player;
        if (!state.isActive() || player == null || !NativeTurnoutCatalog.contains(
                player.getMainHandItem().get(ModDataComponents.SELECTED_RAIL_MODEL.get()))
                || state.geometry() == null || state.draft().points().size() != state.draft().kind().pointCount()
                || state.previewFeedback() == Feedback.INVALID_GEOMETRY) {
            if (!shadow) clearModel();
            return;
        }
        if (modelGeometry != state.geometry()) {
            if (shadow) return;
            modelAssembly = NativeTurnoutPreview.assembly(
                    AssemblyPlacementGeometry.definition(state.draft().kind()),
                    state.geometry(), state.draft().points().getFirst().position(), state.draft().gauge());
            modelGeometry = state.geometry();
        }
        MODEL.prepare(modelAssembly);
        MODEL.drawWorld(event);
    }
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS
                || IrisCompat.shadowPass()) return;
        renderModel(event, false);
        var state = ClientTurnoutConstruction.INSTANCE;
        var draft = state.draft(); var geometry = state.geometry();
        if (!state.isActive()) return;
        int color = state.previewFeedback() == Feedback.READY ? 0xFF50E070
                : (state.previewFeedback() == Feedback.START || state.previewFeedback() == Feedback.TURNOUT_A
                        || state.previewFeedback() == Feedback.TURNOUT_B || state.previewFeedback() == Feedback.CROSSING_POINT
                        || state.previewFeedback() == Feedback.CROSSOVER_POINT) ? 0xFFFFCC55 : 0xFFFF5555;
        var previousShader = RenderSystem.getShader();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        var view = RenderSystem.getModelViewStack();
        view.pushMatrix(); view.set(event.getModelViewMatrix()); RenderSystem.applyModelViewMatrix();
        try (var bytes = new ByteBufferBuilder(4096)) {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var buffers = MultiBufferSource.immediate(bytes);
            var camera = event.getCamera().getPosition();
            var stations = new ArrayList<TotalStationRenderer.Placement>(draft.points().size());
            for (int i = 0; i < draft.points().size(); i++) {
                var station = TotalStationRenderer.placement(draft.points().get(i).position(), state.stationDirection(i));
                stations.add(station);
            }
            TotalStationRenderer.render(stations, camera, buffers);
            var lines = buffers.getBuffer(TrackRenderTypes.PREVIEW);
            for (var station : stations) TotalStationRenderer.beam(station, camera, lines, color);
            if (geometry != null) for (var parts : geometry.paths().values()) {
                for (var part : parts) {
                    path(lines, part, draft.gauge(), camera, color);
                    if (part instanceof BezierTrackGeometry bezier) {
                        var start = part.sample(0).position(); var end = part.sample(part.getLength()).position();
                        line(lines, start, new TrackVector(bezier.control1().x(), start.y(), bezier.control1().z()), camera, 0xFF70C8FF);
                        line(lines, end, new TrackVector(bezier.control2().x(), end.y(), bezier.control2().z()), camera, 0xFF70C8FF);
                    }
                }
            }
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
            buffers.endBatch(TrackRenderTypes.PREVIEW);
            var font = Minecraft.getInstance().font;
            for (int i = 0; i < draft.points().size(); i++) {
                String name = java.util.List.of("A", "B", "C", "D").get(i);
                var point = draft.points().get(i).position();
                var pose = new PoseStack();
                pose.translate(point.x() - camera.x, point.y() - camera.y + 1.5, point.z() - camera.z);
                pose.mulPose(event.getCamera().rotation()); pose.scale(0.025f, -0.025f, 0.025f);
                var port = geometry == null ? null : geometry.ports().get(name);
                double direction = port == null ? 0 : ((port.outwardYaw() % 360) + 540) % 360 - 180;
                String label = port == null ? name : String.format(Locale.ROOT, "%s  %.1f°", name, direction);
                if (draft.kind() == TrackConstructionType.DIAMOND_CROSSING
                        || draft.kind() == TrackConstructionType.SINGLE_CROSSOVER) {
                    String prefix = draft.kind() == TrackConstructionType.DIAMOND_CROSSING
                            ? "diamond_crossing_builder" : "single_crossover_builder";
                    label += " " + net.minecraft.network.chat.Component.translatable("item.unrealtrainmod." + prefix + "."
                            + (i < 2 ? "line_one" : "line_two")).getString();
                } else {
                    if (i == 1) label += " " + net.minecraft.network.chat.Component.translatable("commands.unrealtrainmod.turnout.line_one").getString();
                    if (i == 2) label += " " + net.minecraft.network.chat.Component.translatable("commands.unrealtrainmod.turnout.line_two").getString();
                }
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
