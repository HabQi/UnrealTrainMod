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
    private boolean staticMesh;
    private ModelBounds bounds;

    public void render(GuiGraphics graphics, int x, int y, int width, int height,
                       ClientRailModelManager.Model selected, PreviewCamera camera) {
        prepare(selected);
        renderer.render(graphics, x, y, width, height, bounds, camera, this::draw);
    }

    public NativeImage capture(ClientRailModelManager.Model selected) {
        try {
            prepare(selected);
            return renderer.capture(bounds, -180, 90, false,
                    PreviewRenderer.RAIL_THUMBNAIL_SAMPLE_SCALE, this::draw);
        } finally {
            clearMesh();
        }
    }

    private void prepare(ClientRailModelManager.Model selected) {
        // Prepared model identity changes on resource reload, even when the selection keeps its ID.
        // A script that failed elsewhere in the world also forces the cached filtered preview to rebuild.
        boolean scriptFailed = selected.script() != null && selected.script().failed();
        if (model != selected || (scriptFailed && !staticMesh)) {
            clearMesh();
            var b = selected.bounds();
            RailTrackMeshData data = null;
            if (selected.script() != null && !scriptFailed) {
                var geometry = new StraightTrackGeometry(new TrackPoint(0, -selected.period() / 2),
                        new TrackPoint(0, selected.period() / 2), new LinearGrade(0, 0), 1.435);
                var start = geometry.sample(0).position();
                data = selected.script().buildStatic(geometry,
                        BlockPos.containing(start.x(), start.y() + 0.0625, start.z()), 0, 0, 1, selected.period(), true);
                if (data == null) ClientTrackCache.INSTANCE.invalidateScriptedModel(selected);
            }
            staticMesh = data == null;
            if (staticMesh) {
                var batches = new ArrayList<RailTrackMeshData.Batch>();
                addStatic(selected, batches);
                data = new RailTrackMeshData(new TrackVector(0, 0, 0),
                        new TrackMeshData.Bounds(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()), List.copyOf(batches));
            }
            var meshBounds = data.bounds();
            bounds = data.batches().isEmpty() ? b : new ModelBounds((float)meshBounds.minX(), (float)meshBounds.minY(),
                    (float)meshBounds.minZ(), (float)meshBounds.maxX(), (float)meshBounds.maxY(), (float)meshBounds.maxZ());
            mesh = new RailTrackGpuMesh(data);
            model = selected;
        }
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
