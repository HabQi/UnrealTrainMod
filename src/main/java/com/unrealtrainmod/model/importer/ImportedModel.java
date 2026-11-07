/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model.importer;

import com.unrealtrainmod.model.ModelTexture;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.compat.ir.IrModelComponent;
import java.util.List;
import java.util.Map;

/** Block-space geometry and resolved material bindings, without client rendering resources. */
public record ImportedModel(ModelMesh mesh, Map<ModelData.Material, ModelTexture> textures,
                            List<IrModelComponent> irComponents) {
    public ImportedModel {
        textures = Map.copyOf(textures);
        irComponents = List.copyOf(irComponents);
    }
}
