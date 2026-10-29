/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import com.unrealtrainmod.compat.ir.IrBogieGroups;
import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.MaterialRenderMode;
import com.unrealtrainmod.model.importer.ModelImporter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Prepares vehicle geometry and pixels without registering textures or uploading GPU resources. */
public final class ModelPreparer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelPreparer.class);
    /** Images belong to this reload until the manager transfers them to DynamicTextures. */
    public record PreparedReload(Map<ModelSource, PreparedModel> sources,
                                 List<VehicleDefinition> vehicles, Map<ResourceLocation, NativeImage> images) {}

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
        var classified = new LinkedHashMap<ModelSource, List<ModelPart>>();
        var failed = new HashSet<ModelSource>();
        try (var textures = new TexturePool()) {
            for (var definition : definitions) {
                for (var instance : definition.parts()) {
                    var source = instance.source();
                    if (!classified.containsKey(source) && !failed.contains(source)) {
                        try {
                            classified.put(source, classifySource(source, textures));
                        } catch (IOException | RuntimeException e) {
                            failed.add(source);
                            LOGGER.error("Unable to load vehicle model {}", source.model(), e);
                        }
                    }
                }
            }
            var regions = TextureAtlas.build(classified.values(), textures);
            var prepared = new HashMap<ModelSource, PreparedModel>();
            var iterator = classified.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                try {
                    prepared.put(entry.getKey(), prepareSource(entry.getKey(), entry.getValue(), regions));
                } catch (RuntimeException e) {
                    LOGGER.error("Unable to prepare vehicle model {}", entry.getKey().model(), e);
                }
                // Release each source's unmerged CUTOUT arrays as soon as its packed geometry is ready.
                iterator.remove();
            }
            var vehicles = definitions.stream().filter(definition -> definition.parts().stream()
                    .allMatch(instance -> prepared.containsKey(instance.source()))).toList();
            return new PreparedReload(Map.copyOf(prepared), vehicles, textures.takeImages(prepared.values()));
        }
    }

    private static List<ModelPart> classifySource(ModelSource source, TexturePool textures) throws IOException {
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
        summaries.forEach((name, summary) -> LOGGER.info(
                "Model {} material '{}': mode={} ({}); AlphaBlend={}, bakedAlphaBelowOne={}; CUTOUT={}, BLEND={}, MIXED={}, skipped={}",
                source.model(), name, summary.mode == null ? "LEGACY" : summary.mode, summary.reason,
                summary.alphaBlend, summary.reducedAlpha, summary.counts[0], summary.counts[1], summary.counts[2], summary.skipped));
        LOGGER.info("Prepared {} {}: {} parts, {} triangles; CUTOUT={}, BLEND={}, MIXED={}",
                source.format(), source.model(), mesh.parts().size(), mesh.triangleCount(), counts[0], counts[1], counts[2]);
        return List.copyOf(parts);
    }

    private static PreparedModel prepareSource(ModelSource source, List<ModelPart> parts,
                                               Map<ResourceLocation, TextureAtlas.Region> regions) {
        var originalVisible = parts.stream().filter(ModelPart::visible).flatMap(part -> part.batches().stream())
                .filter(batch -> batch.category() == RenderCategory.CUTOUT).toList();
        int beforeAtlas = OpaqueBatchMerger.visibleBatchCount(parts);
        var merged = OpaqueBatchMerger.merge(parts, regions);
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
        var componentParts = new EnumMap<VehiclePartRole, PartSelection>(VehiclePartRole.class);
        if (source.format() == ModelSource.Format.IR_OBJ) {
            for (var role : VehiclePartRole.values()) {
                var selection = PartSelection.grouped(visible.stream()
                        .filter(part -> IrBogieGroups.role(part.name()) == role).toList());
                componentParts.put(role, selection);
                // Register these range objects before GPU upload; all alias the existing packed meshes.
                allBatches.addAll(selection.batches());
            }
        }
        var mergedVisible = merged.visibleBatches().stream().filter(batch -> batch.category() == RenderCategory.CUTOUT).toList();
        LOGGER.info("Model {}: visible CUTOUT batches {} -> {}; triangles {} -> {}",
                source.model(), originalVisible.size(), mergedVisible.size(),
                originalVisible.stream().mapToInt(batch -> batch.indexCount() / 3).sum(),
                mergedVisible.stream().mapToInt(batch -> batch.indexCount() / 3).sum());
        LOGGER.info("Model {}: atlas visible CUTOUT batches {} -> {}",
                source.model(), beforeAtlas, mergedVisible.size());
        return new PreparedModel(merged.parts(), new PartSelection(visible, merged.visibleBatches()),
                Map.copyOf(partsByName), Map.copyOf(componentParts), List.copyOf(allBatches));
    }
}
