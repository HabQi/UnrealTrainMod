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
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.geometry.VerticalTrackGeometry;
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
        boolean invalid = draft.end() != null && state.previewFeedback() != Feedback.READY;
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
                marker(lines, draft.end().position(), camera, red, green, 0.18);
            }
            var geometry = state.geometry();
            if (geometry != null) {
                int steps = Math.max(1, (int)Math.ceil(geometry.getLength() / 0.5));
                TrackPose a = geometry.sample(0);
                double railCenter = (draft.gauge() + RAIL_WIDTH) / 2;
                TrackVector[][] previous = {section(a, -railCenter), section(a, railCenter)};
                for (int step = 1; step <= steps; step++) {
                    TrackPose b = geometry.sample(geometry.getLength() * step / steps);
                    line(lines, a.position(), b.position(), camera, red, green);
                    for (int side = 0; side < 2; side++) {
                        TrackVector[] start = previous[side], end = section(b, side == 0 ? -railCenter : railCenter);
                        for (int i = 0; i < 4; i++) {
                            line(lines, start[i], end[i], camera, red, green);
                            if (step == 1) line(lines, start[i], start[(i + 1) % 4], camera, red, green);
                            if (step == steps) line(lines, end[i], end[(i + 1) % 4], camera, red, green);
                        }
                        previous[side] = end;
                    }
                    a = b;
                }
            } else if (draft.end() != null) {
                line(lines, draft.start().position(), draft.end().position(), camera, red, green);
            }
            if (draft.end() != null && (state.startCurve() != null || state.endCurve() != null)) {
                handle(lines, draft.start().position(),
                        TrackPlacementGeometry.control(draft.start().position(), draft.end().position(), state.startCurve()), camera);
                handle(lines, draft.end().position(),
                        TrackPlacementGeometry.control(draft.end().position(), draft.start().position(), state.endCurve()), camera);
            } else if (draft.end() == null && state.startCurve() != null) {
                var p = draft.start().position();
                var curve = state.startCurve();
                double angle = Math.toRadians(curve.angle());
                handle(lines, p, new TrackPoint(p.x() - Math.sin(angle) * curve.length(),
                        p.z() + Math.cos(angle) * curve.length()), camera);
            }
            verticalHandles(lines, state, camera);
            buffers.endBatch(TrackRenderTypes.PREVIEW);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }

    private static void handle(VertexConsumer lines, TrackVector node, TrackPoint control, Vec3 camera) {
        TrackVector point = new TrackVector(control.x(), node.y(), control.z());
        line(lines, node, point, camera, 0.2f, 0.7f, 1);
        marker(lines, point, camera, 0.2f, 0.7f, 1, 0.12);
    }

    private static void verticalHandles(VertexConsumer lines, ClientTrackConstruction state, Vec3 camera) {
        var draft = state.draft();
        var start = state.previewVertical(true);
        var end = state.previewVertical(false);
        if (start == null && end == null) return;
        if (draft.end() == null) {
            var node = draft.start().position();
            var tangent = state.endpointTangent(true, start.referenceYaw());
            var point = new TrackVector(node.x() + tangent.x() * start.length(),
                    node.y() + start.gradeAlong(tangent) / 1000 * start.length(), node.z() + tangent.z() * start.length());
            verticalHandle(lines, node, point, camera);
            return;
        }
        try {
            var horizontal = state.horizontalGeometry();
            if (horizontal == null) return;
            var controls = TrackPlacementGeometry.verticalControls(horizontal, draft.start().position().y(),
                    draft.end().position().y(), start, end);
            verticalHandle(lines, draft.start().position(), VerticalTrackGeometry.controlPosition(horizontal, controls.get(0)), camera);
            verticalHandle(lines, draft.end().position(), VerticalTrackGeometry.controlPosition(horizontal, controls.get(1)), camera);
        } catch (IllegalArgumentException exception) {
            // Keep endpoint markers and the horizontal handles visible for invalid drafts.
        }
    }

    private static void verticalHandle(VertexConsumer lines, TrackVector node, TrackVector point, Vec3 camera) {
        line(lines, node, point, camera, 1, 0.35f, 1);
        marker(lines, point, camera, 1, 0.35f, 1, 0.12);
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
        marker(lines, p, camera, red, green, 0.12);
    }

    private static void marker(VertexConsumer lines, TrackVector p, Vec3 camera, float red, float green, double radius) {
        marker(lines, p, camera, red, green, 0.2f, radius);
    }

    private static void marker(VertexConsumer lines, TrackVector p, Vec3 camera, float red, float green, float blue, double radius) {
        double x = p.x() - camera.x, y = p.y() - camera.y, z = p.z() - camera.z;
        LevelRenderer.renderLineBox(new PoseStack(), lines, x - radius, y - radius, z - radius,
                x + radius, y + radius, z + radius, red, green, blue, 1);
    }

    private static void line(VertexConsumer lines, TrackVector a, TrackVector b, Vec3 camera, float red, float green) {
        line(lines, a, b, camera, red, green, 0.2f);
    }

    private static void line(VertexConsumer lines, TrackVector a, TrackVector b, Vec3 camera, float red, float green, float blue) {
        double x = b.x() - a.x(), y = b.y() - a.y(), z = b.z() - a.z();
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0) return;
        float nx = (float)(x / length), ny = (float)(y / length), nz = (float)(z / length);
        lines.addVertex((float)(a.x() - camera.x), (float)(a.y() - camera.y), (float)(a.z() - camera.z))
                .setColor(red, green, blue, 1).setNormal(nx, ny, nz);
        lines.addVertex((float)(b.x() - camera.x), (float)(b.y() - camera.y), (float)(b.z() - camera.z))
                .setColor(red, green, blue, 1).setNormal(nx, ny, nz);
    }
}
