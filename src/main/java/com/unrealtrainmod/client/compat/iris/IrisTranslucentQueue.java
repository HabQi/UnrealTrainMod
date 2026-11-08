/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/** Draw cached vehicle transparency after Iris has copied opaque depth and finished deferred lighting. */
public final class IrisTranslucentQueue {
    private record Draw(IrisGeometryCache.Mesh mesh, RenderType type, Matrix4f view, int light) {}

    static final class Batch {
        private final int[] identity = IrisCompat.currentIdentity();
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final LinkedHashMap<RenderType, List<Draw>> materials = new LinkedHashMap<>();
        private final List<Draw> lights = new ArrayList<>();

        void add(IrisGeometryCache.Mesh mesh, RenderType type, Matrix4f view, int light, boolean lightLayer) {
            var draw = new Draw(mesh, type, view, light);
            if (lightLayer) lights.add(draw);
            else materials.computeIfAbsent(type, ignored -> new ArrayList<>()).add(draw);
        }

        private void draw() {
            IrisCompat.restoreTrackIdentity(identity);
            try (var metrics = IrisRenderStats.beginTranslucentDraw(); var scope = IrisDrawScope.entity()) {
                for (var material : materials.values()) for (var draw : material) {
                    scope.drawCached(draw.mesh(), draw.type(), draw.view(), projection, draw.light());
                    IrisRenderStats.gpu(draw.mesh().vertices / 3);
                }
                for (var draw : lights) {
                    scope.drawCached(draw.mesh(), draw.type(), draw.view(), projection, draw.light());
                    IrisRenderStats.lightGpu(draw.mesh().vertices / 3);
                }
            }
        }
    }

    private static final List<Batch> BATCHES = new ArrayList<>();

    private IrisTranslucentQueue() {}

    static Batch begin() {
        var batch = new Batch();
        BATCHES.add(batch);
        return batch;
    }

    public static void draw() {
        if (BATCHES.isEmpty()) return;
        int[] identity = IrisCompat.currentIdentity();
        try {
            for (var batch : BATCHES) batch.draw();
        } finally {
            BATCHES.clear();
            IrisCompat.restoreTrackIdentity(identity);
        }
    }

    static void clear() { BATCHES.clear(); }
}
