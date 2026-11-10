/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.mixin;

import com.unrealtrainmod.client.ChineseFontPolicy;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class ChineseFontOptionsMixin {
    @Inject(method = "load(Z)V", at = @At("RETURN"))
    private void unrealtrainmod$loadFontOption(boolean limited, CallbackInfo callback) {
        ChineseFontPolicy.apply((Options)(Object)this);
    }
}
