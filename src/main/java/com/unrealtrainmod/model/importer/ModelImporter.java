/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model.importer;

import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.model.ModelTexture;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.IrModelLoader;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.MqoParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Resolves format-specific geometry and material rules before client preparation. */
public final class ModelImporter {
    private ModelImporter() {}

    public static ImportedModel load(ModelSource source) throws IOException {
        ModelData model;
        Map<String, ModelTexture> bindings;
        boolean ir = source.format() == ModelSource.Format.IR_OBJ;
        if (ir) {
            var imported = IrModelLoader.load(source.model());
            model = imported.model();
            bindings = imported.textures();
        } else {
            try (var input = source.model().open()) {
                model = MqoParser.parse(input, StandardCharsets.UTF_8);
            }
            bindings = source.textures();
        }

        var unmatched = new HashSet<>(source.materialRenderModes().keySet());
        unmatched.remove("default");
        for (var material : model.materials()) unmatched.remove(material.name());
        for (var name : unmatched) {
            UnrealTrainMod.LOGGER.warn("Model {}: materialRenderModes['{}'] matches no material", source.model(), name);
        }
        var mesh = ModelMesh.prepare(model, source.scale(), source.smoothing(), ir);
        if (mesh.fanFallbackCount() > 0) {
            UnrealTrainMod.LOGGER.warn("IR {}: used OBJ corner-order triangle fans for {} polygon faces that could not be ear-clipped",
                    source.model(), mesh.fanFallbackCount());
        }

        // An unassigned OBJ face and a named MTL material can both be called "default".
        // Resolve by name, but retain the full material so untextured faces cannot inherit another binding.
        var textures = new HashMap<ModelData.Material, ModelTexture>();
        var warned = new HashSet<String>();
        for (var part : mesh.parts()) {
            for (var batch : part.batches()) {
                var material = batch.material();
                var binding = ir && material.texture() == null ? null : bindings.get(material.name());
                if (binding == null && !ir) binding = bindings.get("default");
                if (binding != null) {
                    textures.put(material, binding);
                } else if (!ir && warned.add(material.name())) {
                    UnrealTrainMod.LOGGER.warn("RTM {}: no texture for material '{}'; using white", source.model(), material.name());
                }
            }
        }
        return new ImportedModel(mesh, textures);
    }
}
