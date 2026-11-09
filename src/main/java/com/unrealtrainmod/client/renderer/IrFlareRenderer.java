/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import com.unrealtrainmod.client.compat.iris.IrisEmissivePass;
import com.unrealtrainmod.compat.ir.IrLightingDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Small transient billboards only; no vehicle vertices or world references are retained. */
public final class IrFlareRenderer extends RenderStateShard {
    public record Flare(IrLightingDefinition.Flare definition, ResourceLocation texture,
                        PoseStack.Pose pose, int color, float strength) {}
    private record Draw(ResourceLocation texture, Matrix4f projection, float x, float y, float z,
                        float radius, int color, float alpha, int[] identity) {}
    private static final List<Draw> DRAWS = new ArrayList<>();
    private static final Map<ResourceLocation, RenderType> TYPES = new HashMap<>();
    private static ByteBufferBuilder bytes;
    private static VertexBuffer buffer;
    private static final Matrix4f IDENTITY = new Matrix4f();

    private IrFlareRenderer() { super("utm_ir_flare", () -> {}, () -> {}); }

    public static void enqueue(IrLightingDefinition.Flare flare, ResourceLocation texture, PoseStack.Pose pose,
                               Matrix4f modelView, Matrix4f projection, int color, float strength) {
        var view = new Matrix4f(modelView).mul(pose.pose());
        var point = flare.center();
        var center = view.transformPosition(new Vector3f((float)point.x(), (float)point.y(), (float)point.z()));
        if (center.z >= 0 || center.lengthSquared() == 0) return;
        var direction = view.transformDirection(new Vector3f(0, 0, flare.direction())).normalize();
        float facing = -new Vector3f(center).normalize().dot(direction);
        if (facing <= 0) return;
        float scale = Math.max(view.transformDirection(new Vector3f(1, 0, 0)).length(),
                view.transformDirection(new Vector3f(0, 1, 0)).length());
        // Move toward the viewer slightly, retaining depth occlusion by other surfaces.
        center.fma(-0.01f / center.length(), center);
        DRAWS.add(new Draw(texture, new Matrix4f(projection), center.x, center.y, center.z,
                flare.size() * scale / 2, color, Math.clamp(strength * facing * facing, 0, 1),
                IrisCompat.active() ? IrisCompat.currentIdentity() : null));
    }

    private static RenderType type(ResourceLocation texture) {
        return TYPES.computeIfAbsent(texture, key -> RenderType.create("utm_ir_flare", DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES, 256, false, false, RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)
                        .setLayeringState(new LayeringStateShard("utm_ir_flare_emission",
                                () -> IrisEmissivePass.setDrawing(true), () -> IrisEmissivePass.setDrawing(false)))
                        .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false)));
    }

    public static void draw() {
        if (DRAWS.isEmpty()) return;
        try {
            if (bytes == null) bytes = new ByteBufferBuilder(2048);
            if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            DRAWS.sort(Comparator.comparingDouble(Draw::z));
            if (IrisCompat.active()) {
                try (var scope = IrisDrawScope.entity()) {
                    for (var draw : DRAWS) {
                        scope.endBatch();
                        IrisCompat.restoreTrackIdentity(draw.identity());
                        upload(draw);
                        IrisDrawScope.draw(buffer, type(draw.texture()), IDENTITY, draw.projection());
                    }
                }
            } else {
                try (var state = new ModelRenderState(false)) {
                    for (var draw : DRAWS) {
                        state.use(type(draw.texture()), true, true);
                        upload(draw);
                        buffer.bind();
                        buffer.drawWithShader(IDENTITY, draw.projection(), RenderSystem.getShader());
                        VertexBuffer.unbind();
                    }
                }
            }
        } finally { clear(); }
    }

    private static void upload(Draw draw) {
        var mesh = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        vertex(mesh, draw, -1, -1, 0, 1); vertex(mesh, draw, 1, -1, 1, 1); vertex(mesh, draw, 1, 1, 1, 0);
        vertex(mesh, draw, -1, -1, 0, 1); vertex(mesh, draw, 1, 1, 1, 0); vertex(mesh, draw, -1, 1, 0, 0);
        buffer.bind();
        buffer.upload(mesh.buildOrThrow());
        VertexBuffer.unbind();
    }

    private static void vertex(BufferBuilder mesh, Draw draw, int x, int y, float u, float v) {
        mesh.addVertex(draw.x() + x * draw.radius(), draw.y() + y * draw.radius(), draw.z())
                .setColor((draw.color() >>> 16) & 255, (draw.color() >>> 8) & 255, draw.color() & 255,
                        Math.round(255 * draw.alpha()))
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
    }

    public static void clear() { DRAWS.clear(); }
    public static void release() {
        clear();
        TYPES.clear();
        if (buffer != null) { buffer.close(); buffer = null; }
        if (bytes != null) { bytes.close(); bytes = null; }
    }
}
