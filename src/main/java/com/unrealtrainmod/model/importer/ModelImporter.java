/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.model.ModelTexture;
import com.unrealtrainmod.compat.ir.IrModelLoader;
import com.unrealtrainmod.compat.ir.IrModelComponents;
import com.unrealtrainmod.compat.ir.IrModelComponent;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.MqoModelInput;
import com.unrealtrainmod.model.MqoParser;
import com.unrealtrainmod.resource.cache.ModelCache;
import com.unrealtrainmod.resource.cache.ModelCacheCodecs;
import com.unrealtrainmod.resource.cache.ModelCacheCodecs.VehicleGeometry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;

/** Resolves format-specific geometry and material rules before client preparation. */
public final class ModelImporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelImporter.class);
    private ModelImporter() {}

    /** Parses authored MQO geometry without preparing a mesh; the caller owns the stream. */
    public static ModelData parseMqo(InputStream input) throws IOException {
        return MqoParser.parse(input, StandardCharsets.UTF_8);
    }

    public static ImportedModel load(ModelSource source, ModelCache cache) throws IOException {
        boolean ir = source.format() == ModelSource.Format.IR_OBJ;
        String options = "vehicle:" + source.format() + ":" + Float.floatToIntBits(source.scale())
                + ":" + source.smoothing() + ":" + ir;
        var geometry = cache.load(source.model(), "mesh", options, ModelCacheCodecs.VEHICLE_GEOMETRY,
                () -> loadGeometry(source));
        var bindings = ir ? geometry.textures() : source.textures();

        var unmatched = new HashSet<>(source.materialRenderModes().keySet());
        unmatched.remove("default");
        unmatched.removeAll(geometry.materialNames());
        for (var name : unmatched) {
            LOGGER.warn("Model {}: materialRenderModes['{}'] matches no material", source.model(), name);
        }
        var mesh = geometry.mesh();
        if (mesh.fanFallbackCount() > 0) {
            LOGGER.warn("IR {}: used OBJ corner-order triangle fans for {} polygon faces that could not be ear-clipped",
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
                    LOGGER.warn("RTM {}: no texture for material '{}'; using white", source.model(), material.name());
                }
            }
        }
        return new ImportedModel(mesh, textures, geometry.irComponents());
    }

    private static VehicleGeometry loadGeometry(ModelSource source) throws IOException {
        ModelData model;
        Map<String, ModelTexture> bindings = Map.of();
        boolean ir = source.format() == ModelSource.Format.IR_OBJ;
        if (ir) {
            var imported = IrModelLoader.load(source.model());
            model = imported.model();
            bindings = imported.textures();
        } else {
            try (var input = MqoModelInput.open(source.model().open(), source.model().entry())) {
                model = parseMqo(input);
            }
        }
        var components = ir ? IrModelComponents.read(model, source.scale(), source.model().toString()) : List.<IrModelComponent>of();
        var mesh = ModelMesh.prepare(model, source.scale(), source.smoothing(), ir);
        return new VehicleGeometry(mesh, model.materials().stream().map(ModelData.Material::name).toList(), bindings, components);
    }
}
