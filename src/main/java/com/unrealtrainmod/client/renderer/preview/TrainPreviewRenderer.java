/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.preview;

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
            renderer.render(graphics, x, y, width, height, model.bounds(), camera, pose -> {
                queue.submit(model, pose, LightTexture.FULL_BRIGHT);
                queue.drawOpaque();
                queue.drawTranslucent();
            });
        } finally {
            queue.clear();
        }
    }

    @Override
    public void close() {
        try { renderer.close(); }
        finally { queue.close(); }
    }
}
