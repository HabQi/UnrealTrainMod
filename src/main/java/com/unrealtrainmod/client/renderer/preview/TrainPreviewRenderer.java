/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.preview;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.ModelRenderQueue;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;

/** Vehicle scene queue, independent of the world and rail previews. */
public final class TrainPreviewRenderer implements AutoCloseable {
    private final ModelRenderQueue queue = new ModelRenderQueue(true);
    private final PreviewRenderer renderer = new PreviewRenderer();

    public void render(GuiGraphics graphics, int x, int y, int width, int height,
                       LoadedModel model, PreviewCamera camera) {
        queue.clear();
        try {
            renderer.render(graphics, x, y, width, height, model.bounds(), camera, pose -> draw(model, pose));
        } finally {
            queue.clear();
        }
    }

    public NativeImage capture(LoadedModel model) {
        queue.clear();
        try {
            return renderer.capture(model.bounds(), -90, 22, true,
                    PreviewRenderer.VEHICLE_THUMBNAIL_SAMPLE_SCALE, pose -> draw(model, pose));
        } finally {
            queue.clear();
        }
    }

    private void draw(LoadedModel model, PoseStack pose) {
        queue.submit(model, pose, LightTexture.FULL_BRIGHT);
        queue.drawOpaque();
        queue.drawTranslucent();
    }

    @Override
    public void close() {
        try { renderer.close(); }
        finally { queue.close(); }
    }
}
