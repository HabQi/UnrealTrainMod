/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.MqoParser;
import com.unrealtrainmod.model.RtmAsset;
import com.unrealtrainmod.model.TrainModels;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

public final class ClientModelManager extends SimplePreparableReloadListener<ClientModelManager.PreparedReload> {
    public static final ClientModelManager INSTANCE = new ClientModelManager();
    public static final ResourceLocation WHITE = id("textures/model/white.png");
    private Map<ResourceLocation, LoadedModel> models = Map.of();
    private Map<TrainModels.Source, LoadedPart> parts = Map.of();
    private Set<ResourceLocation> textures = Set.of();

    public record RenderBatch(IndexedMesh mesh, ResourceLocation texture, boolean translucent, boolean doubleSided) {}
    public record LoadedPart(List<RenderBatch> batches, GpuModel gpu) {}
    public record LoadedInstance(LoadedPart model, ModelData.Position offset) {}
    public record LoadedModel(List<LoadedInstance> instances, ModelBounds bounds) {}
    public record PreparedPart(List<RenderBatch> batches, List<GpuModel.PreparedPart> opaqueParts) {}
    public record PreparedReload(Map<TrainModels.Source, PreparedPart> parts,
                                 List<TrainModels.Definition> vehicles, Map<RtmAsset, NativeImage> images) {}

    private ClientModelManager() {}
    public LoadedModel model(ResourceLocation id) { return id == null ? null : models.get(id); }

    @Override
    protected PreparedReload prepare(ResourceManager resources, ProfilerFiller profiler) {
        var prepared = new HashMap<TrainModels.Source, PreparedPart>();
        var failed = new HashSet<TrainModels.Source>();
        var images = new HashMap<RtmAsset, NativeImage>();
        var vehicles = new ArrayList<TrainModels.Definition>();
        try {
            for (var definition : TrainModels.all()) {
                boolean complete = true;
                for (var instance : definition.parts()) {
                    var source = instance.source();
                    if (!prepared.containsKey(source) && !failed.contains(source)) {
                        try {
                            prepared.put(source, preparePart(source, images));
                        } catch (IOException | RuntimeException e) {
                            failed.add(source);
                            UnrealTrainMod.LOGGER.error("Unable to load RTM model {}", source.model(), e);
                        }
                    }
                    if (failed.contains(source)) complete = false;
                }
                if (complete) vehicles.add(definition);
            }
            return new PreparedReload(Map.copyOf(prepared), List.copyOf(vehicles), images);
        } catch (RuntimeException | Error e) {
            images.values().forEach(NativeImage::close);
            throw e;
        }
    }

    private PreparedPart preparePart(TrainModels.Source source, Map<RtmAsset, NativeImage> images) throws IOException {
        ModelMesh mesh;
        try (var input = source.model().open()) {
            mesh = ModelMesh.prepare(MqoParser.parse(input, StandardCharsets.UTF_8), TrainModels.MQO_SCALE, source.smoothing());
        }
        var batches = new ArrayList<RenderBatch>();
        var opaque = new ArrayList<GpuModel.PreparedPart>();
        var warned = new HashSet<String>();
        for (var part : mesh.parts()) for (var batch : part.batches()) {
            var material = batch.material();
            var binding = source.textures().get(material.name());
            if (binding == null) binding = source.textures().get("default");
            ResourceLocation texture = WHITE;
            if (binding != null) {
                var asset = binding.asset();
                if (!images.containsKey(asset)) {
                    try (var input = asset.open()) {
                        images.put(asset, NativeImage.read(input));
                    }
                }
                texture = textureId(asset);
            } else if (warned.add(material.name())) {
                UnrealTrainMod.LOGGER.warn("RTM {}: no texture for material '{}'; using white", source.model(), material.name());
            }
            boolean translucent = material.alpha() < 0.999f || (binding != null && binding.alphaBlend());
            boolean doubleSided = !source.cullFaces() || material.doubleSided();
            batches.add(new RenderBatch(batch.mesh(), texture, translucent, doubleSided));
            if (!translucent) opaque.add(new GpuModel.PreparedPart(texture, doubleSided, batch.mesh()));
        }
        UnrealTrainMod.LOGGER.info("Prepared RTM MQO {}: {} parts, {} triangles", source.model(), mesh.parts().size(), mesh.triangleCount());
        return new PreparedPart(List.copyOf(batches), List.copyOf(opaque));
    }

    @Override
    protected void apply(PreparedReload prepared, ResourceManager resources, ProfilerFiller profiler) {
        RenderSystem.assertOnRenderThread();
        close();
        var textureManager = Minecraft.getInstance().getTextureManager();
        var registered = new HashSet<ResourceLocation>();
        var uploaded = new HashMap<TrainModels.Source, LoadedPart>();
        try {
            for (var entry : prepared.images().entrySet()) {
                var textureId = textureId(entry.getKey());
                var texture = new DynamicTexture(entry.getValue());
                try {
                    textureManager.register(textureId, texture);
                    registered.add(textureId);
                } catch (RuntimeException | Error e) {
                    texture.close();
                    throw e;
                }
            }
            for (var entry : prepared.parts().entrySet()) {
                GpuModel gpu = null;
                if (!entry.getValue().opaqueParts().isEmpty()) {
                    try {
                        gpu = GpuModel.upload(entry.getValue().opaqueParts());
                    } catch (RuntimeException e) {
                        UnrealTrainMod.LOGGER.error("Unable to upload RTM model {}; using buffered rendering", entry.getKey().model(), e);
                    }
                }
                uploaded.put(entry.getKey(), new LoadedPart(entry.getValue().batches(), gpu));
            }
            var loaded = new HashMap<ResourceLocation, LoadedModel>();
            for (var vehicle : prepared.vehicles()) {
                var instances = vehicle.parts().stream()
                        .map(instance -> new LoadedInstance(uploaded.get(instance.source()), instance.offset())).toList();
                loaded.put(vehicle.id(), new LoadedModel(instances, vehicle.bounds()));
            }
            parts = Map.copyOf(uploaded);
            textures = Set.copyOf(registered);
            models = Map.copyOf(loaded);
        } catch (RuntimeException | Error e) {
            releaseParts(uploaded);
            registered.forEach(textureManager::release);
            throw e;
        } finally {
            // Registered DynamicTextures own their NativeImages until the next reload or shutdown.
            prepared.images().forEach((asset, pixels) -> {
                if (!registered.contains(textureId(asset))) pixels.close();
            });
        }
    }

    public void close() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::close);
            return;
        }
        models = Map.of();
        releaseParts(parts);
        parts = Map.of();
        var textureManager = Minecraft.getInstance().getTextureManager();
        textures.forEach(textureManager::release);
        textures = Set.of();
    }

    private static void releaseParts(Map<TrainModels.Source, LoadedPart> parts) {
        parts.values().forEach(part -> { if (part.gpu() != null) part.gpu().close(); });
    }

    private static ResourceLocation textureId(RtmAsset asset) {
        return id("textures/rtm/" + TrainModels.hash(asset.archive().getFileName() + "\0" + asset.entry()));
    }

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, path); }
}
