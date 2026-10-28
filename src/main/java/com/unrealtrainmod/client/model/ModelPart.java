/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.renderer.RenderBatch;
import java.util.List;

public record ModelPart(int index, String name, boolean visible, List<RenderBatch> batches) {
    public ModelPart { batches = List.copyOf(batches); }
}

