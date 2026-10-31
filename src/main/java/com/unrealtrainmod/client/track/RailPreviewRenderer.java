/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.client.renderer.preview.PreviewRenderer;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/** Shows one authored rail module using the same textured buffers as world rails. */
public final class RailPreviewRenderer implements AutoCloseable {
    private final PreviewRenderer renderer = new PreviewRenderer();
    private ClientRailModelManager.Model model;
    private RailTrackGpuMesh mesh;

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
        if (model != selected) {
            clearMesh();
            var b = selected.bounds();
            var batches = new ArrayList<RailTrackMeshData.Batch>();
            if (selected.script() == null) {
                for (var batch : selected.batches()) {
                    batches.add(new RailTrackMeshData.Batch(batch.texture(), batch.doubleSided(), batch.mesh()));
                }
            } else {
                // The preview shows one authored module; apply the same part filter as world generation.
                for (var part : selected.parts()) {
                    if (!selected.script().shouldRender(part.name(), 0, 1)) continue;
                    for (var batch : part.batches()) {
                        batches.add(new RailTrackMeshData.Batch(batch.texture(), batch.doubleSided(), batch.mesh()));
                    }
                }
            }
            mesh = new RailTrackGpuMesh(new RailTrackMeshData(new TrackVector(0, 0, 0),
                    new TrackMeshData.Bounds(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()), List.copyOf(batches)), true);
            model = selected;
        }
        try {
            if (!mesh.isUploaded()) mesh.upload(null, Integer.MAX_VALUE);
            mesh.draw(pose.last().pose(), RenderSystem.getProjectionMatrix());
        } finally {
            VertexBuffer.unbind();
        }
    }

    public void clearMesh() {
        if (mesh != null) mesh.close();
        mesh = null;
        model = null;
    }

    @Override
    public void close() {
        try { clearMesh(); }
        finally { renderer.close(); }
    }
}
