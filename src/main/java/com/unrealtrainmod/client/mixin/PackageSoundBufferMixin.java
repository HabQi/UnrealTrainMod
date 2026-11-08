/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import com.unrealtrainmod.client.sound.PackageSoundLibrary;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shares the vanilla static-channel cache without exposing ZIP resources as a resource pack. */
@Mixin(SoundBufferLibrary.class)
public abstract class PackageSoundBufferMixin {
    @Shadow @Final private Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache;

    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
    private void unrealtrainmod$packageBuffer(ResourceLocation path,
                                               CallbackInfoReturnable<CompletableFuture<SoundBuffer>> callback) {
        var future = PackageSoundLibrary.buffer(path);
        if (future != null) callback.setReturnValue(cache.computeIfAbsent(path, ignored -> future));
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void unrealtrainmod$invalidatePackages(CallbackInfo callback) {
        PackageSoundLibrary.invalidate();
    }
}
