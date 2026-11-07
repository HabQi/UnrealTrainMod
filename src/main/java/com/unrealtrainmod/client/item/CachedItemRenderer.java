/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.item;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.StaticModelShader;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisGeometryCache;
import com.unrealtrainmod.client.compat.iris.IrisItemRenderer;
import com.unrealtrainmod.client.renderer.ModelRenderQueue;
import com.unrealtrainmod.client.renderer.preview.PreviewRenderer;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

/** Owns static item GPU geometry and demand-generated GUI screenshots for one resource generation. */
public final class CachedItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CachedItemRenderer.class);
    private static CachedItemRenderer instance;
    private final IdentityHashMap<CachedObjModel, Entry> entries = new IdentityHashMap<>();
    private final LinkedHashSet<Entry> pendingIcons = new LinkedHashSet<>();
    private final ModelRenderQueue world = new ModelRenderQueue("item");
    private final ModelRenderQueue gui = new ModelRenderQueue("item_gui", true);
    private final PreviewRenderer capture = new PreviewRenderer();
    private long generation;
    private int nextIcon;

    private static final class Entry {
        final CachedObjModel model;
        GpuModel gpu;
        boolean uploadAttempted, iconFailed;
        ResourceLocation icon;
        RenderType iconType;
        Entry(CachedObjModel model) { this.model = model; }
    }

    private CachedItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static CachedItemRenderer get() {
        if (instance == null) instance = new CachedItemRenderer();
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffers, int light, int overlay) {
        RenderSystem.assertOnRenderThread();
        var renderer = Minecraft.getInstance().getItemRenderer();
        if (!(renderer.getItemModelShaper().getItemModel(stack) instanceof CachedObjModel model)) return;
        long start = ItemRenderStats.begin();
        boolean shadow = start != 0 && IrisCompat.shadowPass();
        var path = ItemRenderStats.Path.FALLBACK;
        var reason = ItemRenderStats.Reason.NONE;
        int draws = 0;
        long triangles = 0;
        try {
            var geometry = model.geometry();
            if (geometry == null || stack.hasFoil() || overlay != OverlayTexture.NO_OVERLAY) {
                reason = geometry == null ? ItemRenderStats.Reason.GEOMETRY
                        : stack.hasFoil() ? ItemRenderStats.Reason.FOIL : ItemRenderStats.Reason.OVERLAY;
                standard(model, stack, context, pose, buffers, light, overlay);
                return;
            }
            var entry = entries.computeIfAbsent(model, Entry::new);
            if (context == ItemDisplayContext.GUI) {
                if (entry.icon != null) {
                    icon(entry, pose, buffers);
                    path = ItemRenderStats.Path.ICON;
                } else {
                    if (!entry.iconFailed) pendingIcons.add(entry);
                    reason = entry.iconFailed ? ItemRenderStats.Reason.ICON_FAILED : ItemRenderStats.Reason.ICON_PENDING;
                    standard(model, stack, context, pose, buffers, light, overlay);
                }
                return;
            }
            if (IrisCompat.active()) {
                // Iris owns forwarding wrappers which are not BufferSource subclasses.
                draws = IrisItemRenderer.render(geometry.batches(), pose.last(), buffers, light);
                if (draws >= 0) path = ItemRenderStats.Path.IRIS;
                else reason = switch (draws) {
                    case IrisItemRenderer.UNSUPPORTED_BUFFER -> ItemRenderStats.Reason.IRIS_BUFFER;
                    case IrisItemRenderer.UNSUPPORTED_POSE -> ItemRenderStats.Reason.IRIS_POSE;
                    case IrisItemRenderer.CACHE_UNAVAILABLE -> ItemRenderStats.Reason.IRIS_CACHE;
                    default -> throw new IllegalStateException("Unknown Iris item render result: " + draws);
                };
            } else if (!(buffers instanceof MultiBufferSource.BufferSource source)) {
                reason = ItemRenderStats.Reason.NATIVE_BUFFER;
            } else if (!nativeGpu(entry)) {
                reason = ItemRenderStats.Reason.NATIVE_GPU;
            } else {
                source.endBatch();
                drawNative(entry, world, pose, light);
                draws = geometry.batches().size();
                path = ItemRenderStats.Path.NATIVE;
            }
            if (path == ItemRenderStats.Path.FALLBACK) {
                draws = 0;
                standard(model, stack, context, pose, buffers, light, overlay);
            } else {
                for (var batch : geometry.batches()) triangles += batch.indexCount() / 3;
            }
        } finally {
            ItemRenderStats.finish(context, shadow, start, path, reason, draws, triangles);
        }
    }

    private boolean nativeGpu(Entry entry) {
        if (StaticModelShader.state() == null) return false;
        if (!entry.uploadAttempted) {
            entry.uploadAttempted = true;
            int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try { entry.gpu = GpuModel.upload(entry.model.geometry().batches()); }
            catch (RuntimeException exception) { LOGGER.warn("Cannot upload item geometry; retaining standard model rendering", exception); }
            finally {
                BufferUploader.invalidate();
                GlStateManager._glBindVertexArray(vao);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, array);
            }
        }
        return entry.gpu != null;
    }

    private static void drawNative(Entry entry, ModelRenderQueue queue, PoseStack pose, int light) {
        try {
            queue.submitBatches(entry.gpu, entry.model.geometry().batches(), pose.last(), light, true);
            queue.drawOpaque();
        } finally { queue.clear(); }
    }

    private static void standard(CachedObjModel model, ItemStack stack, ItemDisplayContext context,
                                  PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        try {
            if (context == ItemDisplayContext.GUI) {
                // ItemRenderer has already shifted from centered coordinates to the model's [0,1] space.
                pose.translate(0.5, 0.5, 0.5);
                model.original().applyTransform(context, pose, false);
                pose.translate(-0.5, -0.5, -0.5);
            }
            var renderer = Minecraft.getInstance().getItemRenderer();
            for (var pass : model.original().getRenderPasses(stack, true)) {
                for (var type : pass.getRenderTypes(stack, true)) {
                    var target = ItemRenderer.getFoilBufferDirect(buffers, type, true, stack.hasFoil());
                    renderer.renderModelLists(pass, stack, light, overlay, pose, target);
                }
            }
        } finally { pose.popPose(); }
    }

    private static void icon(Entry entry, PoseStack pose, MultiBufferSource buffers) {
        var target = buffers.getBuffer(entry.iconType);
        var matrix = pose.last().pose();
        target.addVertex(matrix, 0, 1, 0.5f).setUv(0, 0);
        target.addVertex(matrix, 0, 0, 0.5f).setUv(0, 1);
        target.addVertex(matrix, 1, 0, 0.5f).setUv(1, 1);
        target.addVertex(matrix, 1, 1, 0.5f).setUv(1, 0);
    }

    public static void onFramePost(RenderFrameEvent.Post event) {
        if (instance == null) return;
        instance.generateIcon();
        ItemRenderStats.endFrame();
    }

    private void generateIcon() {
        if (pendingIcons.isEmpty() || Minecraft.getInstance().getOverlay() != null) return;
        var iterator = pendingIcons.iterator();
        var entry = iterator.next();
        iterator.remove();
        long start = ItemRenderStats.begin();
        int boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            if (!nativeGpu(entry)) { entry.iconFailed = true; return; }
            var pixels = capture.captureItem(256, pose -> {
                entry.model.original().applyTransform(ItemDisplayContext.GUI, pose, false);
                pose.translate(-0.5, -0.5, -0.5);
                drawNative(entry, gui, pose, LightTexture.FULL_BRIGHT);
            });
            DynamicTexture texture;
            try { texture = new DynamicTexture(pixels); }
            catch (RuntimeException | Error exception) { pixels.close(); throw exception; }
            var id = ClientModelManager.id("textures/item_icons/" + generation + "/" + nextIcon++);
            try {
                Minecraft.getInstance().getTextureManager().register(id, texture);
                entry.icon = id;
                entry.iconType = ItemIconRenderType.create(id);
            } catch (RuntimeException | Error exception) {
                Minecraft.getInstance().getTextureManager().release(id);
                texture.close();
                entry.icon = null;
                entry.iconType = null;
                throw exception;
            }
            ItemRenderStats.generated(start);
        } catch (RuntimeException exception) {
            entry.iconFailed = true;
            LOGGER.warn("Cannot capture item icon; retaining the three-dimensional GUI model", exception);
        } finally { RenderSystem.bindTexture(boundTexture); }
    }

    /** Model baking has completed on the render thread; release the previous resource generation. */
    public static void invalidate() {
        if (instance == null) return;
        RenderSystem.assertOnRenderThread();
        var renderer = instance;
        renderer.pendingIcons.clear();
        renderer.world.close();
        renderer.gui.close();
        renderer.capture.close();
        for (var entry : renderer.entries.values()) {
            if (entry.gpu != null) entry.gpu.close();
            if (entry.icon != null) Minecraft.getInstance().getTextureManager().release(entry.icon);
            for (var batch : entry.model.geometry().batches()) IrisGeometryCache.releaseMesh(batch.mesh());
        }
        renderer.entries.clear();
        renderer.generation++;
        renderer.nextIcon = 0;
        ItemRenderStats.reset();
    }
}
