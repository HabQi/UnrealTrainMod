/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.StaticModelShader;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import com.unrealtrainmod.client.compat.iris.IrisGeometryCache;
import com.unrealtrainmod.client.compat.iris.IrisRenderTypes;
import com.unrealtrainmod.client.item.ItemGeometry;
import com.unrealtrainmod.client.renderer.ModelRenderQueue;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

/** A shared, transient endpoint instrument. Model and beam use the same optical axis. */
public final class TotalStationRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/Construction");
    private static final ModelResourceLocation BASE = model("total_station_base");
    private static final ModelResourceLocation TELESCOPE = model("total_station_telescope");
    private static final double FOOT_OFFSET = -0.3;
    private static final double PIVOT_HEIGHT = 1.38;
    private static final double LENS_FORWARD = 0.16;
    private static final double BEAM_LENGTH = 4;
    private static final ModelRenderQueue QUEUE = new ModelRenderQueue("construction");
    private static Entry base, telescope;

    private static final class Entry {
        final List<BakedQuad> quads;
        final ItemGeometry geometry;
        final long triangles;
        GpuModel gpu;
        boolean uploadAttempted;

        Entry(BakedModel model) {
            quads = ItemGeometry.quads(model);
            geometry = ItemGeometry.extract(quads);
            triangles = geometry == null ? 0 : geometry.batches().stream().mapToLong(b -> b.indexCount() / 3).sum();
        }
    }

    private record PartDraw(Entry entry, PoseStack.Pose pose, int light) {}
    private record Fallback(PartDraw draw, ConstructionRenderStats.Reason reason) {}

    private TotalStationRenderer() {}

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(
                UnrealTrainMod.MODID, "construction/" + name));
    }

    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(BASE);
        event.register(TELESCOPE);
    }

    /** Capture immutable indexed geometry once for this baked resource generation. */
    public static void onModelsBaked(ModelEvent.BakingCompleted event) {
        close();
        base = new Entry(event.getModelManager().getModel(BASE));
        telescope = new Entry(event.getModelManager().getModel(TELESCOPE));
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) releaseGpu();
    }

    public static void onFramePost(RenderFrameEvent.Post event) { ConstructionRenderStats.endFrame(); }

    private static void releaseGpu() {
        RenderSystem.assertOnRenderThread();
        QUEUE.close();
        for (var entry : new Entry[]{base, telescope}) {
            if (entry == null) continue;
            if (entry.gpu != null) { entry.gpu.close(); entry.gpu = null; }
            entry.uploadAttempted = false;
            if (entry.geometry != null) for (var batch : entry.geometry.batches()) {
                IrisGeometryCache.releaseMesh(batch.mesh());
            }
        }
        ConstructionRenderStats.reset();
    }

    public static void close() {
        releaseGpu();
        base = telescope = null;
    }

    record Placement(TrackVector endpoint, TrackVector direction, float yaw, float pitch) {}

    static Placement placement(TrackVector endpoint, TrackVector direction) {
        double horizontal = Math.hypot(direction.x(), direction.z());
        double length = Math.hypot(horizontal, direction.y());
        return new Placement(endpoint,
                new TrackVector(direction.x() / length, direction.y() / length, direction.z() / length),
                (float)Math.atan2(direction.x(), direction.z()),
                (float)-Math.atan2(direction.y(), horizontal));
    }

    static TrackVector horizontalDirection(double yaw) {
        double angle = Math.toRadians(yaw);
        return new TrackVector(-Math.sin(angle), 0, Math.cos(angle));
    }

    static void render(List<Placement> stations, Vec3 camera, MultiBufferSource.BufferSource buffers) {
        long start = ConstructionRenderStats.begin();
        boolean iris = IrisCompat.active();
        boolean shadow = iris && IrisCompat.shadowPass();
        try {
            var draws = new ArrayList<PartDraw>(stations.size() * 2);
            for (var station : stations) prepare(station, camera, draws);
            var fallbacks = new ArrayList<Fallback>();
            if (iris) renderIris(draws, fallbacks, buffers, shadow);
            else {
                renderNative(draws, fallbacks, shadow);
                renderFallbacks(fallbacks, buffers, shadow);
            }
        } finally {
            QUEUE.clear();
            ConstructionRenderStats.finish(shadow, start, stations.size());
        }
    }

    private static void prepare(Placement station, Vec3 camera, List<PartDraw> draws) {
        var minecraft = Minecraft.getInstance();
        var endpoint = station.endpoint();
        int light = LevelRenderer.getLightColor(minecraft.level,
                BlockPos.containing(endpoint.x(), endpoint.y() + FOOT_OFFSET + PIVOT_HEIGHT, endpoint.z()));
        var pose = new PoseStack();
        pose.translate(endpoint.x() - camera.x, endpoint.y() + FOOT_OFFSET - camera.y, endpoint.z() - camera.z);
        pose.mulPose(Axis.YP.rotation(station.yaw()));
        draws.add(new PartDraw(base, pose.last().copy(), light));
        pose.translate(0, PIVOT_HEIGHT, 0);
        pose.mulPose(Axis.XP.rotation(station.pitch()));
        pose.translate(0, -PIVOT_HEIGHT, 0);
        draws.add(new PartDraw(telescope, pose.last().copy(), light));
    }

    private static void renderNative(List<PartDraw> draws, List<Fallback> fallbacks, boolean shadow) {
        for (var draw : draws) {
            var entry = draw.entry();
            ConstructionRenderStats.Reason reason = entry.geometry == null ? ConstructionRenderStats.Reason.GEOMETRY
                    : StaticModelShader.state() == null ? ConstructionRenderStats.Reason.NATIVE_SHADER
                    : !upload(entry, shadow) ? ConstructionRenderStats.Reason.NATIVE_UPLOAD : null;
            if (reason != null) {
                fallbacks.add(new Fallback(draw, reason));
                continue;
            }
            QUEUE.submitBatches(entry.gpu, entry.geometry.batches(), draw.pose(), draw.light(), true);
            ConstructionRenderStats.part(shadow, ConstructionRenderStats.Path.NATIVE,
                    entry.geometry.batches().size(), entry.triangles, 0, null);
        }
        QUEUE.drawOpaque();
    }

    private static boolean upload(Entry entry, boolean shadow) {
        if (entry.uploadAttempted) return entry.gpu != null;
        entry.uploadAttempted = true;
        int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            entry.gpu = GpuModel.upload(entry.geometry.batches());
            ConstructionRenderStats.uploaded(shadow);
        } catch (RuntimeException exception) {
            LOGGER.warn("Cannot upload construction model geometry; using standard fallback", exception);
        } finally {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, array);
        }
        return entry.gpu != null;
    }

    private static void renderIris(List<PartDraw> draws, List<Fallback> fallbacks,
                                   MultiBufferSource.BufferSource buffers, boolean shadow) {
        try (var scope = IrisDrawScope.preview()) {
            var baseMeshes = irisMeshes(base);
            var telescopeMeshes = irisMeshes(telescope);
            var view = RenderSystem.getModelViewMatrix();
            var projection = RenderSystem.getProjectionMatrix();
            for (var draw : draws) {
                var entry = draw.entry();
                var meshes = entry == base ? baseMeshes : telescopeMeshes;
                if (meshes == null) {
                    fallbacks.add(new Fallback(draw, entry.geometry == null ? ConstructionRenderStats.Reason.GEOMETRY
                            : ConstructionRenderStats.Reason.IRIS_CACHE));
                    continue;
                }
                var matrix = new Matrix4f(view).mul(draw.pose().pose());
                var batches = entry.geometry.batches();
                for (int i = 0; i < batches.size(); i++) {
                    var batch = batches.get(i);
                    scope.drawCached(meshes[i], IrisRenderTypes.get(batch.texture(), batch.doubleSided(), false, false),
                            matrix, projection, draw.light());
                }
                ConstructionRenderStats.part(shadow, ConstructionRenderStats.Path.IRIS,
                        batches.size(), entry.triangles, 0, null);
            }
            // Flush exceptional CPU draws inside the same neutral entity identity and state boundary.
            if (!fallbacks.isEmpty()) {
                scope.endBatch();
                renderFallbacks(fallbacks, buffers, shadow);
            }
        }
    }

    private static void renderFallbacks(List<Fallback> fallbacks, MultiBufferSource.BufferSource buffers, boolean shadow) {
        if (fallbacks.isEmpty()) return;
        var vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        for (var fallback : fallbacks) {
            var draw = fallback.draw();
            for (var quad : draw.entry().quads) {
                vertices.putBulkData(draw.pose(), quad, 1, 1, 1, 1, draw.light(), OverlayTexture.NO_OVERLAY);
            }
            ConstructionRenderStats.part(shadow, ConstructionRenderStats.Path.FALLBACK, 0, 0,
                    draw.entry().quads.size(), fallback.reason());
        }
        buffers.endBatch();
    }

    private static IrisGeometryCache.Mesh[] irisMeshes(Entry entry) {
        if (entry.geometry == null) return null;
        var batches = entry.geometry.batches();
        var meshes = new IrisGeometryCache.Mesh[batches.size()];
        for (int i = 0; i < batches.size(); i++) {
            var batch = batches.get(i);
            meshes[i] = IrisGeometryCache.requestStatic(batch.mesh(), batch.firstIndex(), batch.indexCount());
            if (meshes[i] == null) return null;
        }
        return meshes;
    }

    static void beam(Placement station, Vec3 camera, VertexConsumer lines, int color) {
        var p = station.endpoint();
        var d = station.direction();
        double x = p.x() + d.x() * LENS_FORWARD - camera.x;
        double y = p.y() + FOOT_OFFSET + PIVOT_HEIGHT + d.y() * LENS_FORWARD - camera.y;
        double z = p.z() + d.z() * LENS_FORWARD - camera.z;
        lines.addVertex((float)x, (float)y, (float)z).setColor(color)
                .setNormal((float)d.x(), (float)d.y(), (float)d.z());
        lines.addVertex((float)(x + d.x() * BEAM_LENGTH), (float)(y + d.y() * BEAM_LENGTH),
                (float)(z + d.z() * BEAM_LENGTH)).setColor(color)
                .setNormal((float)d.x(), (float)d.y(), (float)d.z());
    }
}
