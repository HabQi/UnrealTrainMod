/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.renderer.RenderBatch;
import java.util.List;
import java.util.Map;

/** CPU geometry and selections awaiting render-thread upload. */
public record PreparedModel(List<ModelPart> parts, PartSelection visibleParts,
                            Map<String, PartSelection> partsByName, List<RenderBatch> batches) {}
