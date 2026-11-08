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
    private static final class Draw {
        private IrisGeometryCache.Mesh mesh;
        private RenderType type;
        private Matrix4f view;
        private int light;

        void set(IrisGeometryCache.Mesh mesh, RenderType type, Matrix4f view, int light) {
            this.mesh = mesh;
            this.type = type;
            this.view = view;
            this.light = light;
        }

        void clear() {
            mesh = null;
            type = null;
            view = null;
        }
    }

    static final class Batch {
        private final int[] identity = new int[3];
        private final Matrix4f projection = new Matrix4f();
        private final List<Draw> draws = new ArrayList<>();
        private final LinkedHashMap<RenderType, List<Draw>> materials = new LinkedHashMap<>();
        private final List<List<Draw>> materialLists = new ArrayList<>();
        private final List<Draw> lights = new ArrayList<>();
        private int drawCount, materialCount;

        private void capture() {
            IrisCompat.currentIdentity(identity);
            projection.set(RenderSystem.getProjectionMatrix());
        }

        void add(IrisGeometryCache.Mesh mesh, RenderType type, Matrix4f view, int light, boolean lightLayer) {
            if (drawCount == draws.size()) draws.add(new Draw());
            var draw = draws.get(drawCount++);
            draw.set(mesh, type, view, light);
            if (lightLayer) lights.add(draw);
            else {
                var group = materials.get(type);
                if (group == null) {
                    if (materialCount == materialLists.size()) materialLists.add(new ArrayList<>());
                    group = materialLists.get(materialCount++);
                    materials.put(type, group);
                }
                group.add(draw);
            }
        }

        private void draw() {
            IrisCompat.restoreTrackIdentity(identity);
            try (var metrics = IrisRenderStats.beginTranslucentDraw(); var scope = IrisDrawScope.entity()) {
                for (var material : materials.values()) for (var draw : material) {
                    scope.drawCached(draw.mesh, draw.type, draw.view, projection, draw.light);
                    IrisRenderStats.gpu(draw.mesh.vertices / 3);
                }
                for (var draw : lights) {
                    scope.drawCached(draw.mesh, draw.type, draw.view, projection, draw.light);
                    IrisRenderStats.lightGpu(draw.mesh.vertices / 3);
                }
            }
        }

        private void clear() {
            materials.clear();
            for (int i = 0; i < materialCount; i++) materialLists.get(i).clear();
            lights.clear();
            for (int i = 0; i < drawCount; i++) draws.get(i).clear();
            drawCount = materialCount = 0;
        }
    }

    private static final ArrayList<Batch> BATCHES = new ArrayList<>();
    private static final int[] DRAW_IDENTITY = new int[3];
    private static int batchCount;

    private IrisTranslucentQueue() {}

    static Batch begin() {
        if (batchCount == BATCHES.size()) BATCHES.add(new Batch());
        var batch = BATCHES.get(batchCount);
        batch.capture();
        batchCount++;
        return batch;
    }

    public static void draw() {
        if (batchCount == 0) return;
        try {
            IrisCompat.currentIdentity(DRAW_IDENTITY);
            try {
                for (int i = 0; i < batchCount; i++) BATCHES.get(i).draw();
            } finally {
                IrisCompat.restoreTrackIdentity(DRAW_IDENTITY);
            }
        } finally {
            clear();
        }
    }

    /** Discard frame references while retaining the command and list capacity. */
    static void clear() {
        for (int i = 0; i < batchCount; i++) BATCHES.get(i).clear();
        batchCount = 0;
    }

    /** World and pipeline teardown also release the pooled workspace. Geometry belongs to its cache. */
    static void release() {
        clear();
        BATCHES.clear();
        BATCHES.trimToSize();
    }
}
