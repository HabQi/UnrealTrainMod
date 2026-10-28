/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.IrModelLoader;
import com.unrealtrainmod.model.MaterialRenderMode;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.MqoParser;
import com.unrealtrainmod.model.RtmAsset;
import com.unrealtrainmod.model.TrainModels;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
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
    private Map<TrainModels.Source, LoadedSource> sources = Map.of();
    private Set<ResourceLocation> textures = Set.of();

    /** A null mode retains the legacy package policy; category describes the classified geometry. */
    public record RenderBatch(IndexedMesh mesh, ResourceLocation texture, RenderCategory category,
                              MaterialRenderMode mode, boolean doubleSided, float[] centers) {
        public RenderBatch(IndexedMesh mesh, ResourceLocation texture, RenderCategory category,
                           MaterialRenderMode mode, boolean doubleSided) {
            this(mesh, texture, category, mode, doubleSided, triangleCenters(mesh, category));
        }

        public int alphaPass(boolean blended) {
            if (mode == MaterialRenderMode.OPAQUE) return StaticModelShader.FORCE_OPAQUE;
            if (mode == MaterialRenderMode.CUTOUT) return StaticModelShader.FORCE_CUTOUT;
            if (mode == MaterialRenderMode.TRANSLUCENT) return StaticModelShader.FORCE_TRANSLUCENT;
            if (blended) return StaticModelShader.BLENDED;
            return category == RenderCategory.MIXED ? StaticModelShader.MIXED_OPAQUE : StaticModelShader.LEGACY_CUTOUT;
        }

        private static float[] triangleCenters(IndexedMesh mesh, RenderCategory category) {
            if (category == RenderCategory.CUTOUT) return new float[0];
            int[] indices = mesh.indices();
            float[] vertices = mesh.vertices(), centers = new float[indices.length];
            for (int i = 0; i < indices.length; i += 3) {
                int a = indices[i] * 8, b = indices[i + 1] * 8, c = indices[i + 2] * 8;
                for (int axis = 0; axis < 3; axis++) {
                    centers[i + axis] = (vertices[a + axis] + vertices[b + axis] + vertices[c + axis]) / 3;
                }
            }
            return centers;
        }
    }
    public record LoadedPart(int index, String name, boolean visible, List<RenderBatch> batches) {
        public LoadedPart { batches = List.copyOf(batches); }
    }

    /** Immutable selection of source-local parts, shared by GPU and buffered rendering. */
    public static final class PartSelection implements IntPredicate {
        private final List<LoadedPart> parts;
        private final BitSet indices = new BitSet();

        private PartSelection(List<LoadedPart> parts) {
            this.parts = List.copyOf(parts);
            for (var part : parts) indices.set(part.index());
        }

        public List<LoadedPart> parts() { return parts; }
        @Override public boolean test(int index) { return indices.get(index); }
    }

    /** Owns GPU geometry; selections only reference its parts and never own rendering resources. */
    public record LoadedSource(List<LoadedPart> parts, PartSelection visibleParts,
                               Map<String, PartSelection> partsByName, GpuModel gpu) {}
    public record LoadedInstance(LoadedSource model, ModelData.Position offset) {}
    public record LoadedModel(List<LoadedInstance> instances, ModelBounds bounds) {}
    public record PreparedSource(List<LoadedPart> parts, PartSelection visibleParts,
                                 Map<String, PartSelection> partsByName, List<RenderBatch> batches) {}
    public record PreparedReload(Map<TrainModels.Source, PreparedSource> sources,
                                 List<TrainModels.Definition> vehicles, Map<RtmAsset, NativeImage> images) {}

    /** Accumulates across objects so each source/material emits one classification summary. */
    private static final class MaterialSummary {
        private final MaterialRenderMode mode;
        private final String reason;
        private final int[] counts = new int[RenderCategory.values().length];
        private int skipped;
        private boolean alphaBlend, reducedAlpha;

        private MaterialSummary(MaterialRenderMode mode, String reason) {
            this.mode = mode;
            this.reason = reason;
        }
    }

    private ClientModelManager() {}
    public LoadedModel model(ResourceLocation id) { return id == null ? null : models.get(id); }

    @Override
    protected PreparedReload prepare(ResourceManager resources, ProfilerFiller profiler) {
        var prepared = new HashMap<TrainModels.Source, PreparedSource>();
        var failed = new HashSet<TrainModels.Source>();
        var images = new HashMap<RtmAsset, NativeImage>();
        var alpha = new HashMap<RtmAsset, TextureAlpha>();
        var vehicles = new ArrayList<TrainModels.Definition>();
        try {
            for (var definition : TrainModels.all()) {
                boolean complete = true;
                for (var instance : definition.parts()) {
                    var source = instance.source();
                    if (!prepared.containsKey(source) && !failed.contains(source)) {
                        try {
                            prepared.put(source, prepareSource(source, images, alpha));
                        } catch (IOException | RuntimeException e) {
                            failed.add(source);
                            UnrealTrainMod.LOGGER.error("Unable to load vehicle model {}", source.model(), e);
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

    private PreparedSource prepareSource(TrainModels.Source source, Map<RtmAsset, NativeImage> images,
                                         Map<RtmAsset, TextureAlpha> alphaCache) throws IOException {
        ModelData model;
        var bindings = source.textures();
        if (source.format() == TrainModels.Format.IR_OBJ) {
            var imported = IrModelLoader.load(source.model());
            model = imported.model();
            bindings = imported.textures();
        } else {
            try (var input = source.model().open()) {
                model = MqoParser.parse(input, StandardCharsets.UTF_8);
            }
        }
        var unmatched = new HashSet<>(source.materialRenderModes().keySet());
        unmatched.remove("default");
        for (var material : model.materials()) unmatched.remove(material.name());
        for (var name : unmatched) {
            UnrealTrainMod.LOGGER.warn("Model {}: materialRenderModes['{}'] matches no material", source.model(), name);
        }
        var mesh = ModelMesh.prepare(model, source.scale(), source.smoothing(), source.format() == TrainModels.Format.IR_OBJ);
        if (mesh.fanFallbackCount() > 0) {
            UnrealTrainMod.LOGGER.warn("IR {}: used OBJ corner-order triangle fans for {} polygon faces that could not be ear-clipped",
                    source.model(), mesh.fanFallbackCount());
        }
        var parts = new ArrayList<LoadedPart>();
        var visible = new ArrayList<LoadedPart>();
        var named = new HashMap<String, List<LoadedPart>>();
        var allBatches = new ArrayList<RenderBatch>();
        var summaries = new LinkedHashMap<String, MaterialSummary>();
        int[] counts = new int[RenderCategory.values().length];
        var warned = new HashSet<String>();
        for (var part : mesh.parts()) {
            int index = parts.size();
            var batches = new ArrayList<RenderBatch>();
            for (var batch : part.batches()) {
                var material = batch.material();
                var modes = source.materialRenderModes();
                var mode = modes.getOrDefault(material.name(), modes.get("default"));
                var summary = summaries.computeIfAbsent(material.name(), name -> new MaterialSummary(mode,
                        modes.containsKey(name) ? "materialRenderModes exact match"
                                : mode != null ? "materialRenderModes default" : "legacy RTM alpha rules"));
                var binding = source.format() == TrainModels.Format.IR_OBJ && material.texture() == null
                        ? null : bindings.get(material.name());
                if (binding == null && source.format() == TrainModels.Format.MQO) binding = bindings.get("default");
                ResourceLocation texture = WHITE;
                TextureAlpha alpha = TextureAlpha.WHITE;
                if (binding != null) {
                    var asset = binding.asset();
                    if (!images.containsKey(asset)) {
                        try (var input = asset.open()) {
                            images.put(asset, NativeImage.read(input));
                        }
                    }
                    texture = textureId(asset);
                    alpha = alphaCache.computeIfAbsent(asset, ignored -> new TextureAlpha(images.get(asset)));
                } else if (source.format() == TrainModels.Format.MQO && warned.add(material.name())) {
                    UnrealTrainMod.LOGGER.warn("RTM {}: no texture for material '{}'; using white", source.model(), material.name());
                }
                boolean alphaBlend = material.alpha() < 1 || (binding != null && binding.alphaBlend());
                summary.alphaBlend |= binding != null && binding.alphaBlend();
                boolean doubleSided = !source.cullFaces() || material.doubleSided();
                var original = batch.mesh();
                if (!summary.reducedAlpha) {
                    for (int color : original.colors()) if ((color >>> 24) < 255) {
                        summary.reducedAlpha = true;
                        break;
                    }
                }
                IntArrayList[] classified = {new IntArrayList(), new IntArrayList(), new IntArrayList()};
                int[] indices = original.indices();
                for (int i = 0; i < indices.length; i += 3) {
                    var category = alpha.classify(original.vertices(), original.colors(),
                            indices[i], indices[i + 1], indices[i + 2], alphaBlend, mode);
                    if (category == null) {
                        summary.skipped++;
                        continue;
                    }
                    summary.counts[category.ordinal()]++;
                    var target = classified[category.ordinal()];
                    target.add(indices[i]); target.add(indices[i + 1]); target.add(indices[i + 2]);
                }
                for (var category : RenderCategory.values()) {
                    var selected = classified[category.ordinal()];
                    if (selected.isEmpty()) continue;
                    counts[category.ordinal()] += selected.size() / 3;
                    // Categories retain the same vertex/color arrays; upload these vertices only once.
                    var selectedMesh = new IndexedMesh(original.vertices(), original.colors(), selected.toIntArray());
                    batches.add(new RenderBatch(selectedMesh, texture, category, mode, doubleSided));
                }
            }
            allBatches.addAll(batches);
            var loaded = new LoadedPart(index, part.name(), part.visible(), batches);
            parts.add(loaded);
            if (loaded.visible()) visible.add(loaded);
            named.computeIfAbsent(loaded.name(), ignored -> new ArrayList<>()).add(loaded);
        }
        var partsByName = new HashMap<String, PartSelection>();
        named.forEach((name, matching) -> partsByName.put(name, new PartSelection(matching)));
        summaries.forEach((name, summary) -> UnrealTrainMod.LOGGER.info(
                "Model {} material '{}': mode={} ({}); AlphaBlend={}, bakedAlphaBelowOne={}; CUTOUT={}, BLEND={}, MIXED={}, skipped={}",
                source.model(), name, summary.mode == null ? "LEGACY" : summary.mode, summary.reason,
                summary.alphaBlend, summary.reducedAlpha, summary.counts[0], summary.counts[1], summary.counts[2], summary.skipped));
        UnrealTrainMod.LOGGER.info("Prepared {} {}: {} parts, {} triangles; CUTOUT={}, BLEND={}, MIXED={}",
                source.format(), source.model(), mesh.parts().size(), mesh.triangleCount(), counts[0], counts[1], counts[2]);
        return new PreparedSource(List.copyOf(parts), new PartSelection(visible), Map.copyOf(partsByName), List.copyOf(allBatches));
    }

    @Override
    protected void apply(PreparedReload prepared, ResourceManager resources, ProfilerFiller profiler) {
        RenderSystem.assertOnRenderThread();
        close();
        var textureManager = Minecraft.getInstance().getTextureManager();
        var registered = new HashSet<ResourceLocation>();
        var uploaded = new HashMap<TrainModels.Source, LoadedSource>();
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
                uploaded.put(entry.getKey(), new LoadedSource(source.parts(), source.visibleParts(), source.partsByName(), gpu));
            }
            var loaded = new HashMap<ResourceLocation, LoadedModel>();
            for (var vehicle : prepared.vehicles()) {
                var instances = vehicle.parts().stream()
                        .map(instance -> new LoadedInstance(uploaded.get(instance.source()), instance.offset())).toList();
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
        TrainModelRenderer.clearWorld();
        models = Map.of();
        releaseSources(sources);
        sources = Map.of();
        var textureManager = Minecraft.getInstance().getTextureManager();
        textures.forEach(textureManager::release);
        textures = Set.of();
    }

    private static void releaseSources(Map<TrainModels.Source, LoadedSource> sources) {
        sources.values().forEach(source -> { if (source.gpu() != null) source.gpu().close(); });
    }

    private static ResourceLocation textureId(RtmAsset asset) {
        return id("textures/package/" + TrainModels.hash(asset.archive().toAbsolutePath().normalize() + "\0" + asset.entry()));
    }

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, path); }
}
