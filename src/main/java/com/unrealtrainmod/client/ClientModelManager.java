/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.model.ModelPreparer;
import com.unrealtrainmod.client.model.ModelPreparer.PreparedReload;
import com.unrealtrainmod.client.model.TextureLoader;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Owns loaded vehicle textures and GPU geometry, separate from CPU preparation. */
public final class ClientModelManager extends SimplePreparableReloadListener<PreparedReload> {
    public static final ClientModelManager INSTANCE = new ClientModelManager();
    public static final ResourceLocation WHITE = TextureLoader.WHITE;
    private Map<ResourceLocation, LoadedModel> models = Map.of();
    private Map<ModelSource, LoadedModel.Source> sources = Map.of();
    private Set<ResourceLocation> textures = Set.of();

    private ClientModelManager() {}
    public LoadedModel model(ResourceLocation id) { return id == null ? null : models.get(id); }

    @Override
    protected PreparedReload prepare(ResourceManager resources, ProfilerFiller profiler) {
        return ModelPreparer.prepare(VehicleCatalog.all());
    }

    @Override
    protected void apply(PreparedReload prepared, ResourceManager resources, ProfilerFiller profiler) {
        RenderSystem.assertOnRenderThread();
        var pending = new HashMap<>(prepared.images());
        var textureManager = Minecraft.getInstance().getTextureManager();
        var registered = new HashSet<ResourceLocation>();
        var uploaded = new HashMap<ModelSource, LoadedModel.Source>();
        try {
            close();
            for (var entry : prepared.images().entrySet()) {
                var textureId = TextureLoader.textureId(entry.getKey());
                var pixels = pending.remove(entry.getKey());
                DynamicTexture texture;
                try {
                    texture = new DynamicTexture(pixels);
                } catch (RuntimeException | Error e) {
                    pixels.close();
                    throw e;
                }
                try {
                    textureManager.register(textureId, texture);
                    registered.add(textureId);
                } catch (RuntimeException | Error e) {
                    texture.close();
                    throw e;
                }
            }
            for (var entry : prepared.sources().entrySet()) {
                var source = entry.getValue();
                GpuModel gpu = null;
                if (!source.batches().isEmpty()) {
                    try {
                        gpu = GpuModel.upload(source.batches());
                    } catch (RuntimeException e) {
                        UnrealTrainMod.LOGGER.error("Unable to upload vehicle model {}; using buffered rendering", entry.getKey().model(), e);
                    }
                }
                uploaded.put(entry.getKey(), new LoadedModel.Source(source.parts(), source.visibleParts(), source.partsByName(), gpu));
            }
            var loaded = new HashMap<ResourceLocation, LoadedModel>();
            for (var vehicle : prepared.vehicles()) {
                var instances = vehicle.parts().stream()
                        .map(instance -> new LoadedModel.Instance(uploaded.get(instance.source()), instance.offset())).toList();
                loaded.put(vehicle.id(), new LoadedModel(instances, vehicle.bounds()));
            }
            sources = Map.copyOf(uploaded);
            textures = Set.copyOf(registered);
            models = Map.copyOf(loaded);
        } catch (RuntimeException | Error e) {
            releaseSources(uploaded);
            registered.forEach(textureManager::release);
            throw e;
        } finally {
            // Removed images were transferred to DynamicTextures or closed on construction failure.
            pending.values().forEach(pixels -> pixels.close());
        }
    }

    public void close() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::close);
            return;
        }
        TrainModelRenderer.clearWorld();
        models = Map.of();
        releaseSources(sources);
        sources = Map.of();
        var textureManager = Minecraft.getInstance().getTextureManager();
        textures.forEach(textureManager::release);
        textures = Set.of();
    }

    private static void releaseSources(Map<ModelSource, LoadedModel.Source> sources) {
        sources.values().forEach(source -> { if (source.gpu() != null) source.gpu().close(); });
    }

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, path); }
}
