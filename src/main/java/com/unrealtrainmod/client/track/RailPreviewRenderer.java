/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.client.renderer.preview.PreviewRenderer;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import java.util.stream.IntStream;
import net.minecraft.client.gui.GuiGraphics;

/** Shows one authored rail module using the same textured buffers as world rails. */
public final class RailPreviewRenderer implements AutoCloseable {
    private final PreviewRenderer renderer = new PreviewRenderer();
    private ClientRailModelManager.Model model;
    private RailTrackGpuMesh mesh;

    public void render(GuiGraphics graphics, int x, int y, int width, int height,
                       ClientRailModelManager.Model selected, PreviewCamera camera) {
        renderer.render(graphics, x, y, width, height, selected.bounds(), camera, pose -> {
            // Prepared model identity changes on resource reload, even when the selection keeps its ID.
            if (model != selected) {
                clearMesh();
                var b = selected.bounds();
                var batches = IntStream.range(0, selected.batches().size())
                        .mapToObj(i -> new RailTrackMeshData.Batch(i, selected.batches().get(i).mesh())).toList();
                mesh = new RailTrackGpuMesh(new RailTrackMeshData(new TrackVector(0, 0, 0),
                        new TrackMeshData.Bounds(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()), batches), selected, true);
                model = selected;
            }
            try {
                if (!mesh.isUploaded()) mesh.upload(null, Integer.MAX_VALUE);
                mesh.draw(pose.last().pose(), RenderSystem.getProjectionMatrix());
            } finally {
                VertexBuffer.unbind();
            }
        });
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
