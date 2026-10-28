/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.RailModels;
import com.unrealtrainmod.model.RtmAsset;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Rail-only prepared geometry and textures. No vehicle instances, render queues, or script evaluation. */
public final class ClientRailModelManager extends SimplePreparableReloadListener<ClientRailModelManager.Prepared> implements AutoCloseable {
    public static final ClientRailModelManager INSTANCE = new ClientRailModelManager();
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "textures/model/white.png");
    public record Batch(IndexedMesh mesh, ResourceLocation texture, boolean doubleSided) {}
    private record MaterialKey(ResourceLocation texture, boolean doubleSided) {}
    public record Model(List<Batch> batches, double padding, ModelBounds bounds) {
        public Model { batches = List.copyOf(batches); }
    }
    public record Prepared(Map<ResourceLocation, Model> models, Map<RtmAsset, NativeImage> images) {}
    private Map<ResourceLocation, Model> models = Map.of();
    private final Set<ResourceLocation> textures = new HashSet<>();

    private ClientRailModelManager() {}
    Model model(String id) { return id == null ? null : models.get(ResourceLocation.parse(id)); }
    public Model model(ResourceLocation id) { return id == null ? null : models.get(id); }

    private static ResourceLocation textureId(RtmAsset asset) {
        return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "rtm_rail/" + UUID.nameUUIDFromBytes(
                (asset.archive() + "!" + asset.entry()).getBytes(StandardCharsets.UTF_8)));
    }

    private static NativeImage image(RtmAsset asset, Map<RtmAsset, NativeImage> images) throws IOException {
        NativeImage image = images.get(asset);
        if (image == null) {
            try (var input = asset.open()) { image = NativeImage.read(input); }
            images.put(asset, image);
        }
        return image;
    }

    @Override
    protected Prepared prepare(ResourceManager resources, ProfilerFiller profiler) {
        var models = new HashMap<ResourceLocation, Model>();
        var images = new HashMap<RtmAsset, NativeImage>();
        var meshes = new HashMap<RtmAsset, ModelMesh>();
        var sources = new HashMap<RailModels.Source, Model>();
        try {
            for (var definition : RailModels.all()) {
                var source = definition.source();
                try {
                    Model preparedSource = sources.get(source);
                    if (preparedSource != null) {
                        models.put(definition.id(), preparedSource);
                    } else {
                        ModelMesh mesh = meshes.get(source.model());
                        if (mesh == null) {
                            mesh = ModelMesh.prepare(source.data(), (float)RailTrackMeshBuilder.MODEL_SCALE, true);
                            meshes.put(source.model(), mesh);
                        }
                        var grouped = new LinkedHashMap<MaterialKey, List<IndexedMesh>>();
                        var bounds = new ModelBounds.Builder();
                        double padding = 0;
                        for (var part : mesh.parts()) {
                            if (!part.visible()) continue;
                            for (var batch : part.batches()) {
                                if (batch.mesh().indices().length == 0) continue;
                                var material = batch.material();
                                var binding = source.textures().get(material.name());
                                ResourceLocation texture = WHITE;
                                if (binding != null) {
                                    image(binding.asset(), images);
                                    texture = textureId(binding.asset());
                                }
                                grouped.computeIfAbsent(new MaterialKey(texture, material.doubleSided()), ignored -> new ArrayList<>()).add(batch.mesh());
                                float[] v = batch.mesh().vertices();
                                for (int i = 0; i < v.length; i += 8) {
                                    bounds.include(v[i], v[i + 1], v[i + 2]);
                                    double y = v[i + 1] - RailTrackMeshBuilder.TOP_OFFSET;
                                    padding = Math.max(padding, Math.sqrt(v[i] * (double)v[i] + y * y) + Math.abs(v[i + 2]));
                                }
                            }
                        }
                        var batches = new ArrayList<Batch>();
                        grouped.forEach((key, values) -> batches.add(new Batch(merge(values), key.texture(), key.doubleSided())));
                        preparedSource = new Model(batches, padding + RailTrackMeshBuilder.PERIOD, bounds.build());
                        sources.put(source, preparedSource);
                        models.put(definition.id(), preparedSource);
                    }
                } catch (IOException | RuntimeException exception) {
                    UnrealTrainMod.LOGGER.error("Cannot prepare RTM rail {} from {}: {}", definition.railName(), source.model(),
                            exception.getMessage(), exception);
                }
            }
            return new Prepared(Map.copyOf(models), images);
        } catch (RuntimeException | Error exception) {
            images.values().forEach(NativeImage::close);
            throw exception;
        }
    }

    private static IndexedMesh merge(List<IndexedMesh> meshes) {
        if (meshes.size() == 1) return meshes.getFirst();
        int vertices = 0, indices = 0;
        for (var mesh : meshes) {
            vertices = Math.addExact(vertices, mesh.colors().length);
            indices = Math.addExact(indices, mesh.indices().length);
        }
        float[] data = new float[Math.multiplyExact(vertices, 8)];
        int[] colors = new int[vertices], elements = new int[indices];
        int vertexOffset = 0, indexOffset = 0;
        for (var mesh : meshes) {
            System.arraycopy(mesh.vertices(), 0, data, vertexOffset * 8, mesh.vertices().length);
            System.arraycopy(mesh.colors(), 0, colors, vertexOffset, mesh.colors().length);
            for (int index : mesh.indices()) elements[indexOffset++] = vertexOffset + index;
            vertexOffset += mesh.colors().length;
        }
        return new IndexedMesh(data, colors, elements);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resources, ProfilerFiller profiler) {
        RenderSystem.assertOnRenderThread();
        ClientTrackCache.INSTANCE.invalidateMeshes();
        close();
        var manager = Minecraft.getInstance().getTextureManager();
        var pending = new HashMap<>(prepared.images());
        try {
            for (var entry : prepared.images().entrySet()) {
                ResourceLocation id = textureId(entry.getKey());
                NativeImage image = pending.remove(entry.getKey());
                DynamicTexture texture;
                try { texture = new DynamicTexture(image); }
                catch (RuntimeException | Error exception) { image.close(); throw exception; }
                try { manager.register(id, texture); }
                catch (RuntimeException | Error exception) { texture.close(); throw exception; }
                textures.add(id);
            }
            models = prepared.models();
        } catch (RuntimeException | Error exception) {
            pending.values().forEach(NativeImage::close);
            close();
            throw exception;
        }
    }

    @Override
    public void close() {
        var manager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation id : textures) manager.release(id);
        textures.clear();
        TrackRenderTypes.clearTextures();
        models = Map.of();
    }
}
