/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.instancing;

import com.unrealtrainmod.client.GpuModel;
import net.minecraft.resources.ResourceLocation;

/** Requests share one draw only when GPU mesh, canonical index range and material state match. */
public record InstanceBatchKey(GpuModel.Mesh mesh, int firstIndex, int indexCount, ResourceLocation texture,
                               boolean doubleSided, int alphaPass, boolean directionalLighting, float emission) {}
