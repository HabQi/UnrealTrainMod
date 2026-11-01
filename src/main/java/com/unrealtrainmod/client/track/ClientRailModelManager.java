/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unrealtrainmod.compat.rtm.script.RailScriptRuntime;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.track.model.RailDefinition;
import com.unrealtrainmod.track.model.RailCatalog;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelMesh;
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
import javax.script.ScriptException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Rail-only prepared geometry and textures. No vehicle instances or render queues. */
public final class ClientRailModelManager extends SimplePreparableReloadListener<ClientRailModelManager.Prepared> implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientRailModelManager.class);
    public static final ClientRailModelManager INSTANCE = new ClientRailModelManager();
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "textures/model/white.png");
    public record Batch(IndexedMesh mesh, ResourceLocation texture, boolean doubleSided) {}
    /** Authored object and its material batches, retained for legacy static script filtering. */
    public record Part(String name, List<Batch> batches) {
        public Part { batches = List.copyOf(batches); }
    }
    private record MaterialKey(ResourceLocation texture, boolean doubleSided) {}
    private record MeshKey(PackageAsset model, RailDefinition.Format format, float scale) {}
    /**
     * Reference-gauge geometry with the rail top already translated to Y=0. Flat batches keep the
     * source-level material merge; parts retain the authored grouping for scripts.
     */
    public record Model(List<Batch> batches, List<Part> parts, double padding, ModelBounds bounds,
                        RailDefinition.Format format, double period, RailScriptRuntime script) implements AutoCloseable {
        public Model {
            batches = List.copyOf(batches);
            parts = List.copyOf(parts);
        }

        public double scaleForGauge(double gauge) { return format.scaleForGauge(gauge); }

        @Override
        public void close() {
            if (script != null) script.close();
        }
    }
    public record Prepared(Map<ResourceLocation, Model> models, Map<PackageAsset, NativeImage> images) {}
    private Map<ResourceLocation, Model> models = Map.of();
    private final Set<ResourceLocation> textures = new HashSet<>();

    private ClientRailModelManager() {}
    Model model(String id) { return id == null ? null : models.get(ResourceLocation.parse(id)); }
    public Model model(ResourceLocation id) { return id == null ? null : models.get(id); }

    private static ResourceLocation textureId(PackageAsset asset) {
        return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "rtm_rail/" + UUID.nameUUIDFromBytes(
                (asset.archive() + "!" + asset.entry()).getBytes(StandardCharsets.UTF_8)));
    }

    private static NativeImage image(PackageAsset asset, Map<PackageAsset, NativeImage> images) throws IOException {
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
        var images = new HashMap<PackageAsset, NativeImage>();
        var meshes = new HashMap<MeshKey, ModelMesh>();
        var sources = new HashMap<RailDefinition.Source, Model>();
        try {
            for (var definition : RailCatalog.all()) {
                var source = definition.source();
                try {
                    // Scripted definitions are never shared: shouldRenderObject sees the authored railName.
                    Model preparedSource = source.rendererPath() == null ? sources.get(source) : null;
                    if (preparedSource != null) {
                        models.put(definition.id(), preparedSource);
                        continue;
                    }
                    var key = new MeshKey(source.model(), source.format(), source.scale());
                    ModelMesh mesh = meshes.get(key);
                    if (mesh == null) {
                        boolean ir = source.format() == RailDefinition.Format.IR_OBJ;
                        mesh = ModelMesh.prepare(source.data(), source.scale(), !ir, ir);
                        if (mesh.fanFallbackCount() > 0) {
                            LOGGER.warn("IR rail {}: used OBJ corner-order triangle fans for {} polygon faces",
                                    source.model(), mesh.fanFallbackCount());
                        }
                        meshes.put(key, mesh);
                    }
                    var flat = new LinkedHashMap<MaterialKey, List<IndexedMesh>>();
                    var parts = new ArrayList<Part>();
                    boolean scripted = source.rendererPath() != null;
                    var bounds = new ModelBounds.Builder();
                    double padding = 0;
                    for (var part : mesh.parts()) {
                        if (!part.visible()) continue;
                        var partBatches = scripted ? new LinkedHashMap<MaterialKey, List<IndexedMesh>>() : null;
                        for (var batch : part.batches()) {
                            if (batch.mesh().indices().length == 0) continue;
                            var material = batch.material();
                            var binding = source.format() == RailDefinition.Format.IR_OBJ && material.texture() == null
                                    ? null : source.textures().get(material.name());
                            ResourceLocation texture = WHITE;
                            if (binding != null) {
                                image(binding.asset(), images);
                                texture = textureId(binding.asset());
                            }
                            var materialKey = new MaterialKey(texture, material.doubleSided());
                            if (partBatches != null) {
                                partBatches.computeIfAbsent(materialKey, ignored -> new ArrayList<>()).add(batch.mesh());
                            }
                            flat.computeIfAbsent(materialKey, ignored -> new ArrayList<>()).add(batch.mesh());
                            float[] v = batch.mesh().vertices();
                            for (int i = 0; i < v.length; i += 8) {
                                float y = (float)(v[i + 1] - source.topOffset());
                                if (!Float.isFinite(v[i]) || !Float.isFinite(y) || !Float.isFinite(v[i + 2])) {
                                    throw new IOException("Non-finite prepared rail vertex");
                                }
                                bounds.include(v[i], y, v[i + 2]);
                                padding = Math.max(padding, Math.sqrt(v[i] * (double)v[i] + y * (double)y) + Math.abs(v[i + 2]));
                            }
                        }
                        if (partBatches != null && !partBatches.isEmpty()) {
                            parts.add(new Part(part.name(), batches(partBatches, source.topOffset())));
                        }
                    }
                    var flatBatches = new ArrayList<Batch>();
                    flat.forEach((material, values) -> flatBatches.add(new Batch(merge(values, source.topOffset()),
                            material.texture(), material.doubleSided())));
                    RailScriptRuntime script = null;
                    if (source.rendererPath() != null) {
                        try {
                            script = RailScriptRuntime.create(source.rendererPath(), definition.railName(),
                                    definition.railName() + " (" + source.rendererPath().entry() + ")");
                        } catch (IOException | RuntimeException | ScriptException
                                 | NoSuchMethodException | LinkageError exception) {
                            LOGGER.error("Cannot load rail script {}!{} for {}; rendering all static parts: {}",
                                    source.rendererPath().archive(), source.rendererPath().entry(),
                                    definition.railName(), exception.getMessage());
                            LOGGER.debug("Rail script initialisation failure", exception);
                        }
                    }
                    preparedSource = new Model(flatBatches, parts, padding + source.period(), bounds.build(),
                            source.format(), source.period(), script);
                    if (source.rendererPath() == null) sources.put(source, preparedSource);
                    models.put(definition.id(), preparedSource);
                } catch (IOException | RuntimeException exception) {
                    LOGGER.error("Cannot prepare rail {} from {}: {}", definition.railName(), source.model(),
                            exception.getMessage(), exception);
                }
            }
            return new Prepared(Map.copyOf(models), images);
        } catch (RuntimeException | Error exception) {
            images.values().forEach(NativeImage::close);
            models.values().forEach(Model::close);
            throw exception;
        }
    }

    private static List<Batch> batches(Map<MaterialKey, List<IndexedMesh>> grouped, double topOffset) {
        var result = new ArrayList<Batch>();
        grouped.forEach((material, values) -> result.add(new Batch(merge(values, topOffset),
                material.texture(), material.doubleSided())));
        return List.copyOf(result);
    }

    private static IndexedMesh merge(List<IndexedMesh> meshes, double topOffset) {
        if (meshes.size() == 1 && topOffset == 0) return meshes.getFirst();
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
        for (int i = 1; i < data.length; i += 8) data[i] = (float)(data[i] - topOffset);
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
            prepared.models().values().forEach(Model::close);
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
        models.values().forEach(Model::close);
        models = Map.of();
    }
}
