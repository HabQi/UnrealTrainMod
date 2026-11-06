/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.mixin;

import com.unrealtrainmod.client.compat.iris.IrisVehicleReprojection;
import com.unrealtrainmod.client.texture.MaterialTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineMixin {
    @Inject(method = "destroy", at = @At("HEAD"))
    private void unrealtrainmod$releaseResources(CallbackInfo callback) {
        IrisVehicleReprojection.reset();
        MaterialTexture.invalidatePbr();
    }
}
