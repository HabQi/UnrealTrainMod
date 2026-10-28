/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import com.unrealtrainmod.model.importer.ModelImporter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Prepares vehicle geometry and pixels without registering textures or uploading GPU resources. */
public final class ModelPreparer {
    /** Images belong to this reload until the manager transfers them to DynamicTextures. */
    public record PreparedReload(Map<ModelSource, PreparedModel> sources,
                                 List<VehicleDefinition> vehicles, Map<PackageAsset, NativeImage> images) {}

    private ModelPreparer() {}

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

    public static PreparedReload prepare(List<VehicleDefinition> definitions) {
        var prepared = new HashMap<ModelSource, PreparedModel>();
        var failed = new HashSet<ModelSource>();
        var vehicles = new ArrayList<VehicleDefinition>();
        try (var textures = new TexturePool()) {
            for (var definition : definitions) {
                boolean complete = true;
                for (var instance : definition.parts()) {
                    var source = instance.source();
                    if (!prepared.containsKey(source) && !failed.contains(source)) {
                        try {
                            prepared.put(source, prepareSource(source, textures));
                        } catch (IOException | RuntimeException e) {
                            failed.add(source);
                            UnrealTrainMod.LOGGER.error("Unable to load vehicle model {}", source.model(), e);
                        }
                    }
                    if (failed.contains(source)) complete = false;
                }
                if (complete) vehicles.add(definition);
            }
            return new PreparedReload(Map.copyOf(prepared), List.copyOf(vehicles), textures.takeImages(prepared.values()));
        }
    }

    private static PreparedModel prepareSource(ModelSource source, TexturePool textures) throws IOException {
        var imported = ModelImporter.load(source);
        var mesh = imported.mesh();
        var parts = new ArrayList<ModelPart>();
        var summaries = new LinkedHashMap<String, MaterialSummary>();
        int[] counts = new int[RenderCategory.values().length];
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
                var binding = imported.textures().get(material);
                ResourceLocation texture = TextureLoader.WHITE;
                TextureAlpha alpha = TextureAlpha.WHITE;
                if (binding != null) {
                    var shared = textures.load(binding.asset());
                    texture = shared.id();
                    alpha = shared.alpha();
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
            parts.add(new ModelPart(index, part.name(), part.visible(), batches));
        }
        var originalVisible = parts.stream().filter(ModelPart::visible).flatMap(part -> part.batches().stream())
                .filter(batch -> batch.category() == RenderCategory.CUTOUT).toList();
        var merged = OpaqueBatchMerger.merge(parts);
        var visible = merged.parts().stream().filter(ModelPart::visible).toList();
        var named = new HashMap<String, List<ModelPart>>();
        var allBatches = new ArrayList<RenderBatch>();
        for (var part : merged.parts()) {
            allBatches.addAll(part.batches());
            named.computeIfAbsent(part.name(), ignored -> new ArrayList<>()).add(part);
        }
        // Default ranges alias the same meshes as the per-part ranges; GPU upload deduplicates their storage.
        for (var batch : merged.visibleBatches()) {
            if (batch.category() == RenderCategory.CUTOUT) allBatches.add(batch);
        }
        var partsByName = new HashMap<String, PartSelection>();
        named.forEach((name, matching) -> partsByName.put(name, new PartSelection(matching)));
        summaries.forEach((name, summary) -> UnrealTrainMod.LOGGER.info(
                "Model {} material '{}': mode={} ({}); AlphaBlend={}, bakedAlphaBelowOne={}; CUTOUT={}, BLEND={}, MIXED={}, skipped={}",
                source.model(), name, summary.mode == null ? "LEGACY" : summary.mode, summary.reason,
                summary.alphaBlend, summary.reducedAlpha, summary.counts[0], summary.counts[1], summary.counts[2], summary.skipped));
        UnrealTrainMod.LOGGER.info("Prepared {} {}: {} parts, {} triangles; CUTOUT={}, BLEND={}, MIXED={}",
                source.format(), source.model(), mesh.parts().size(), mesh.triangleCount(), counts[0], counts[1], counts[2]);
        var mergedVisible = merged.visibleBatches().stream().filter(batch -> batch.category() == RenderCategory.CUTOUT).toList();
        UnrealTrainMod.LOGGER.info("Model {}: visible CUTOUT batches {} -> {}; triangles {} -> {}",
                source.model(), originalVisible.size(), mergedVisible.size(),
                originalVisible.stream().mapToInt(batch -> batch.indexCount() / 3).sum(),
                mergedVisible.stream().mapToInt(batch -> batch.indexCount() / 3).sum());
        return new PreparedModel(merged.parts(), new PartSelection(visible, merged.visibleBatches()),
                Map.copyOf(partsByName), List.copyOf(allBatches));
    }
}
