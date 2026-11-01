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
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

/** Shows one authored rail module using the same textured buffers as world rails. */
public final class RailPreviewRenderer implements AutoCloseable {
    private final PreviewRenderer renderer = new PreviewRenderer();
    private ClientRailModelManager.Model model;
    private RailTrackGpuMesh mesh;
    private boolean staticMesh;

    public void render(GuiGraphics graphics, int x, int y, int width, int height,
                       ClientRailModelManager.Model selected, PreviewCamera camera) {
        renderer.render(graphics, x, y, width, height, selected.bounds(), camera, pose -> draw(selected, pose));
    }

    public NativeImage capture(ClientRailModelManager.Model selected) {
        try {
            return renderer.capture(selected.bounds(), -180, 90, false,
                    PreviewRenderer.RAIL_THUMBNAIL_SAMPLE_SCALE, pose -> draw(selected, pose));
        } finally {
            clearMesh();
        }
    }

    private void draw(ClientRailModelManager.Model selected, PoseStack pose) {
        // Prepared model identity changes on resource reload, even when the selection keeps its ID.
        // A script that failed elsewhere in the world also forces the cached filtered preview to rebuild.
        boolean scriptFailed = selected.script() != null && selected.script().failed();
        if (model != selected || (scriptFailed && !staticMesh)) {
            clearMesh();
            var b = selected.bounds();
            var batches = new ArrayList<RailTrackMeshData.Batch>();
            if (selected.script() == null || scriptFailed) {
                addStatic(selected, batches);
                staticMesh = true;
            } else {
                // The preview shows one authored module; apply the same part filter as world generation.
                var filter = new RailScriptFilter(selected, BlockPos.ZERO);
                try {
                    for (var part : selected.parts()) {
                        if (!filter.shouldRender(part.name(), 0, 1)) continue;
                        for (var batch : part.batches()) {
                            batches.add(new RailTrackMeshData.Batch(batch.texture(), batch.doubleSided(), batch.mesh()));
                        }
                    }
                } catch (RailScriptFilter.Failure failure) {
                    // Discard the partially filtered preview and let world meshes fall back on the next frame.
                    staticMesh = true;
                    batches.clear();
                    addStatic(selected, batches);
                }
            }
            mesh = new RailTrackGpuMesh(new RailTrackMeshData(new TrackVector(0, 0, 0),
                    new TrackMeshData.Bounds(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()), List.copyOf(batches)), true);
            model = selected;
        }
        try (var state = new TrackRenderState(null)) {
            if (!mesh.isUploaded()) mesh.upload(null, Integer.MAX_VALUE, null);
            mesh.draw(state, pose.last().pose(), RenderSystem.getProjectionMatrix());
        }
    }

    private static void addStatic(ClientRailModelManager.Model model, List<RailTrackMeshData.Batch> batches) {
        for (var batch : model.batches()) {
            batches.add(new RailTrackMeshData.Batch(batch.texture(), batch.doubleSided(), batch.mesh()));
        }
    }

    public void clearMesh() {
        if (mesh != null) mesh.close();
        mesh = null;
        model = null;
        staticMesh = false;
    }

    @Override
    public void close() {
        try { clearMesh(); }
        finally { renderer.close(); }
    }
}
