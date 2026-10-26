/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.MqoParser;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

public final class ClientModelManager extends SimplePreparableReloadListener<ClientModelManager.PreparedModel> {
    public static final ClientModelManager INSTANCE = new ClientModelManager();
    public static final ResourceLocation DESCRIPTION = id("train_models/yz25g.json");
    public static final ResourceLocation WHITE = id("textures/model/white.png");
    private volatile LoadedModel model;

    public record RenderBatch(IndexedMesh mesh, ResourceLocation texture, boolean translucent, boolean doubleSided) {}
    public record LoadedModel(ModelMesh mesh, List<RenderBatch> batches, GpuModel gpu) {}
    public record PreparedModel(ModelMesh mesh, List<RenderBatch> batches,
                                List<GpuModel.PreparedPart> opaqueParts, boolean gpuEnabled) {}

    private ClientModelManager() {}
    public LoadedModel model() { return model; }

    @Override
    protected PreparedModel prepare(ResourceManager resources, ProfilerFiller profiler) {
        try {
            JsonObject description;
            try (var reader = resources.getResourceOrThrow(DESCRIPTION).openAsReader()) {
                description = GsonHelper.parse(reader);
            }
            ResourceLocation source = ResourceLocation.parse(GsonHelper.getAsString(description, "model"));
            float scale = GsonHelper.getAsFloat(description, "scale", 0.01f);
            boolean gpuEnabled = GsonHelper.getAsBoolean(description, "gpu_rendering", true);
            boolean cullFaces = GsonHelper.getAsBoolean(description, "cull_faces", true);
            var encoding = Charset.forName(GsonHelper.getAsString(description, "encoding", "UTF-8"));
            ModelMesh mesh;
            try (var input = resources.getResourceOrThrow(source).open()) {
                mesh = ModelMesh.prepare(MqoParser.parse(input, encoding), scale);
            }
            Map<String, ResourceLocation> remap = new HashMap<>();
            if (description.has("texture_remap")) {
                description.getAsJsonObject("texture_remap").entrySet().forEach(entry ->
                        remap.put(entry.getKey(), ResourceLocation.parse(entry.getValue().getAsString())));
            }
            Map<String, ResourceLocation> resolved = new HashMap<>();
            List<RenderBatch> batches = new ArrayList<>();
            List<GpuModel.PreparedPart> opaqueParts = new ArrayList<>();
            for (var part : mesh.parts()) for (var batch : part.batches()) {
                String reference = batch.material().texture();
                ResourceLocation texture = reference == null ? WHITE : resolved.computeIfAbsent(reference, path -> {
                    String basename = path.replace('\\', '/');
                    basename = basename.substring(basename.lastIndexOf('/') + 1);
                    ResourceLocation target = remap.getOrDefault(path, remap.get(basename));
                    if (target == null || resources.getResource(target).isEmpty()) {
                        UnrealTrainMod.LOGGER.warn("MQO {}: texture '{}' is missing or unmapped; using plain white", source, path);
                        return WHITE;
                    }
                    return target;
                });
                boolean translucent = batch.material().alpha() < 0.999f;
                boolean doubleSided = !cullFaces || batch.material().doubleSided();
                batches.add(new RenderBatch(batch.mesh(), texture, translucent, doubleSided));
                if (gpuEnabled && !translucent) {
                    // CPU-only preparation on the reload worker. OpenGL work happens exclusively in apply().
                    opaqueParts.add(new GpuModel.PreparedPart(texture, doubleSided, batch.mesh()));
                }
            }
            UnrealTrainMod.LOGGER.info("Loaded MQO {}: {} parts, {} triangles",
                    source, mesh.parts().size(), mesh.triangleCount());
            return new PreparedModel(mesh, List.copyOf(batches), List.copyOf(opaqueParts), gpuEnabled);
        } catch (IOException | RuntimeException e) {
            UnrealTrainMod.LOGGER.error("Unable to load YZ25G model; check {} and referenced assets", DESCRIPTION, e);
            return null;
        }
    }

    @Override
    protected void apply(PreparedModel prepared, ResourceManager resources, ProfilerFiller profiler) {
        RenderSystem.assertOnRenderThread();
        GpuModel gpu = null;
        if (prepared != null && prepared.gpuEnabled()) {
            try {
                gpu = GpuModel.upload(prepared.opaqueParts());
                UnrealTrainMod.LOGGER.info("Uploaded static YZ25G mesh: {} batches, {} unique vertices, {} triangle indices, {} MiB GPU geometry",
                        prepared.opaqueParts().size(), gpu.vertexCount(), gpu.indexCount(), gpu.byteSize() / (1024.0 * 1024.0));
            } catch (RuntimeException e) {
                UnrealTrainMod.LOGGER.error("Unable to upload static model geometry; using buffered rendering", e);
            }
        }
        LoadedModel previous = model;
        model = prepared == null ? null : new LoadedModel(prepared.mesh(), prepared.batches(), gpu);
        if (previous != null && previous.gpu() != null) previous.gpu().close();
    }

    public void close() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::close);
            return;
        }
        LoadedModel previous = model;
        model = null;
        if (previous != null && previous.gpu() != null) previous.gpu().close();
    }

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, path); }
}
