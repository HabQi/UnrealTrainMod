/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.wiring.WiringClient;
import com.unrealtrainmod.client.wiring.WiringModelManager;
import com.unrealtrainmod.client.wiring.WiringRenderCache;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WireData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Retained preview objects use the same geometry and wire runtimes as placed structures. */
final class BlueprintScene implements AutoCloseable {
    final Map<UUID, ConnectorData> nodes = new LinkedHashMap<>();
    final Map<UUID, WireData> wires = new LinkedHashMap<>();
    private final Map<UUID, net.minecraft.world.phys.AABB> bounds = new LinkedHashMap<>();
    void set(List<ConnectorData> ns, List<WireData> ws) {
        close(); ns.forEach(n -> nodes.put(n.id(), n)); ws.forEach(w -> wires.put(w.id(), w));
        ns.forEach(n -> bounds.put(n.id(), n.bounds()));
    }
    void render(PoseStack pose, MultiBufferSource buffers, boolean offscreen, Vec3 camera, Frustum frustum, float partial) {
        var mc = Minecraft.getInstance(); long tick = mc.level == null ? 0 : mc.level.getGameTime();
        for (var node : nodes.values()) {
            if (frustum != null && !frustum.isVisible(bounds.get(node.id()))) continue;
            var model = WiringModelManager.INSTANCE.model(node.model()); var p = node.origin().subtract(camera);
            pose.pushPose();
            try {
                pose.translate(p.x, p.y, p.z); pose.mulPose(node.rotation());
                int light = offscreen || mc.level == null ? 0xF000F0 : LevelRenderer.getLightColor(mc.level, BlockPos.containing(node.origin()));
                if (model != null) WiringRenderCache.render(model.draws(), pose, buffers, light, offscreen, true);
                if (model == null || model.definition().empty()) LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
                        -.125, -.125, -.125, .125, .125, .125, model == null ? 1 : .2f, model == null ? 0 : 1, 1, 1);
            } finally { pose.popPose(); }
        }
        for (var wire : wires.values()) {
            var a = nodes.get(wire.first()); var b = nodes.get(wire.second()); if (a == null || b == null) continue;
            if (frustum != null && !frustum.isVisible(WiringClient.bounds(wire, a, b))) continue;
            var model = WiringModelManager.INSTANCE.model(wire.model()); var p = a.wirePoint().subtract(camera);
            pose.pushPose();
            try {
                pose.translate(p.x, p.y, p.z);
                var draws = model == null ? null : WiringClient.wireDraws(wire.id(), model, a, b, tick, partial);
                if (draws != null) {
                    int light = offscreen || mc.level == null ? 0xF000F0 : LevelRenderer.getLightColor(mc.level, BlockPos.containing(a.wirePoint()));
                    WiringRenderCache.render(draws, pose, buffers, light, offscreen, true);
                } else {
                    var out = buffers.getBuffer(RenderType.lines()); var target = b.wirePoint().subtract(a.wirePoint());
                    var normal = target.lengthSqr() > 1e-12 ? target.normalize() : new Vec3(0, 1, 0);
                    out.addVertex(pose.last().pose(), 0, 0, 0).setColor(0xFFFF00FF).setNormal((float)normal.x, (float)normal.y, (float)normal.z);
                    out.addVertex(pose.last().pose(), (float)target.x, (float)target.y, (float)target.z).setColor(0xFFFF00FF)
                            .setNormal((float)normal.x, (float)normal.y, (float)normal.z);
                }
            } finally { pose.popPose(); }
        }
    }
    public void close() { wires.keySet().forEach(WiringClient::release); nodes.clear(); wires.clear(); bounds.clear(); }
}
