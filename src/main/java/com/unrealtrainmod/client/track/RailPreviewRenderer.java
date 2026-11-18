/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.client.renderer.preview.PreviewRenderer;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

/** Shows one authored rail module using the same textured buffers as world rails. */
public final class RailPreviewRenderer implements AutoCloseable {
    private final PreviewRenderer renderer = new PreviewRenderer();
    private ClientRailModelManager.Model model;
    private RailTrackGpuMesh mesh;
    private final TrackStaticBuffers buffers = new TrackStaticBuffers(true);
    private final TrackDrawQueue queue = new TrackDrawQueue();
    private com.unrealtrainmod.client.ModelPreparation.Ticket<RailTrackMeshData> pending;
    private boolean staticMesh;
    private ModelBounds bounds;

    public void render(GuiGraphics graphics, int x, int y, int width, int height,
                       ClientRailModelManager.Model selected, PreviewCamera camera) {
        if (!prepare(selected)) return;
        renderer.render(graphics, x, y, width, height, bounds, camera, this::draw);
    }

    public NativeImage capture(ClientRailModelManager.Model selected) {
        if (!prepare(selected)) return null;
        try { return renderer.capture(bounds, -180, 90, false, PreviewRenderer.RAIL_THUMBNAIL_SAMPLE_SCALE, this::draw); }
        finally { clearMesh(); }
    }

    private boolean prepare(ClientRailModelManager.Model selected) {
        boolean scriptFailed = selected.script() != null && selected.script().failed();
        if (model != selected || scriptFailed && !staticMesh) {
            clearMesh(); model = selected; bounds = selected.bounds();
            if (selected.script() != null && !scriptFailed) {
                var geometry = new StraightTrackGeometry(new TrackPoint(0, -selected.period() / 2),
                        new TrackPoint(0, selected.period() / 2), new LinearGrade(0, 0), 1.435);
                var start = geometry.sample(0).position();
                var snapshot = selected.script().captureStatic(geometry,
                        BlockPos.containing(start.x(), start.y() + 0.0625, start.z()), 1, selected.period(), true);
                if (snapshot != null) pending = com.unrealtrainmod.client.ModelPreparation.geometry(() -> snapshot.build(0, 0),
                        TrackPreparation::size, ignored -> {});
                else ClientTrackCache.INSTANCE.invalidateScriptedModel(selected);
                // If the bounded queue is full, retry capture on a later frame.
                if (snapshot != null && pending == null) { model = null; return false; }
            }
            staticMesh = pending == null;
            if (staticMesh) {
                var batches = new ArrayList<RailTrackMeshData.Batch>(); addStatic(selected, batches);
                mesh = new RailTrackGpuMesh(new RailTrackMeshData(new TrackVector(0, 0, 0),
                        new TrackMeshData.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()), List.copyOf(batches)));
            }
        }
        if (pending != null) {
            if (!pending.ready()) return false;
            try {
                var data = pending.take(); mesh = new RailTrackGpuMesh(data);
                var b = data.bounds();
                if (!data.batches().isEmpty()) bounds = new ModelBounds((float)b.minX(), (float)b.minY(), (float)b.minZ(),
                        (float)b.maxX(), (float)b.maxY(), (float)b.maxZ());
            } catch (RuntimeException exception) {
                selected.script().close(); ClientTrackCache.INSTANCE.invalidateScriptedModel(selected);
                clearMesh(); return false;
            } finally { if (pending != null) { pending.close(); pending = null; } }
        }
        if (mesh == null) return false;
        boolean textures = mesh.texturesReady();
        buffers.beginView();
        if (mesh.isEmpty()) return textures;
        buffers.request(mesh, TrackRenderStats.STRUCTURE_TRACK);
        buffers.prepare(null, null, Integer.MAX_VALUE, null);
        return textures && buffers.ready(mesh);
    }

    private void draw(PoseStack pose) {
        try (var state = new TrackRenderState(null)) {
            buffers.beginView();
            if (mesh.isEmpty()) return;
            buffers.request(mesh, TrackRenderStats.STRUCTURE_TRACK);
            buffers.prepare(null, null, Integer.MAX_VALUE, null);
            queue.begin();
            queue.addStatic(mesh, new Matrix4f(pose.last().pose()), TrackRenderStats.STRUCTURE_TRACK);
            queue.resolve(buffers, null);
            // The preview owns a native shader scope even when Iris is enabled in the world.
            queue.drawPreview(state, RenderSystem.getProjectionMatrix());
        } finally { queue.clear(); }
    }

    private static void addStatic(ClientRailModelManager.Model model, List<RailTrackMeshData.Batch> batches) {
        for (var batch : model.batches()) {
            batches.add(new RailTrackMeshData.Batch(batch.texture(), batch.doubleSided(), batch.mesh()));
        }
    }

    public void clearMesh() {
        if (pending != null) { pending.close(); pending = null; }
        buffers.close();
        queue.close();
        if (mesh != null) mesh.close();
        mesh = null;
        model = null;
        bounds = null;
        staticMesh = false;
    }

    @Override
    public void close() {
        try { clearMesh(); }
        finally { renderer.close(); }
    }
}
