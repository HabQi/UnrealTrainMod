/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.compat.ir.IrModelComponent;
import java.util.List;

/** A visual component only references ranges in the shared source geometry. */
public record IrModelPart(IrModelComponent definition, PartSelection selection, List<IrAnimationBinding> animations) {
    public IrModelPart { animations = List.copyOf(animations); }
}
