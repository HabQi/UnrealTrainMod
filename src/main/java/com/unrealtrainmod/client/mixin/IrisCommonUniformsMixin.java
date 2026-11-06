/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.mixin;

import com.unrealtrainmod.client.compat.iris.IrisVehicleReprojection;
import com.unrealtrainmod.client.compat.iris.IrisEmissivePass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class IrisCommonUniformsMixin {
    @Inject(method = "addDynamicUniforms", at = @At("TAIL"))
    private static void unrealtrainmod$vehicleUniforms(@Coerce Object holder, @Coerce Object fogMode,
                                                      CallbackInfo callback) {
        IrisVehicleReprojection.registerUniforms(holder);
        IrisEmissivePass.registerUniforms(holder);
    }
}
