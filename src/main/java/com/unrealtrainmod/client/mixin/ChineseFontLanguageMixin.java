/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.mixin;

import com.unrealtrainmod.client.ChineseFontPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LanguageSelectScreen.class)
public abstract class ChineseFontLanguageMixin {
    @Inject(method = "onDone()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;"))
    private void unrealtrainmod$applySelectedLanguage(CallbackInfo callback) {
        // Vanilla has assigned the new options.languageCode before starting the language reload.
        ChineseFontPolicy.apply(Minecraft.getInstance().options);
    }
}
