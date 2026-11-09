/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.compat.ir.IrModelComponent;
import java.util.List;
import com.unrealtrainmod.compat.ir.IrLightingDefinition;
import net.minecraft.resources.ResourceLocation;

/** A visual component only references ranges in the shared source geometry. */
public record IrModelPart(IrModelComponent definition, PartSelection selection, List<IrAnimationBinding> animations,
                          IrLightingDefinition.Rule lighting, ResourceLocation flareTexture) {
    public IrModelPart { animations = List.copyOf(animations); }
}
