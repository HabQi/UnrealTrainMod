/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.compat.ir.IrAnimatrix;
import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-thread cache, shared across vehicles; workers only return metadata. */
final class AnimationSoundTimings {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnimationSoundTimings.class);
    private static final Map<PackageAsset, CompletableFuture<Integer>> FRAMES = new HashMap<>();

    private AnimationSoundTimings() {}

    static CompletableFuture<Integer> frames(PackageAsset asset) {
        return FRAMES.computeIfAbsent(asset, key -> CompletableFuture.supplyAsync(() -> {
            try { return IrAnimatrix.countFrames(key); }
            catch (IOException | RuntimeException exception) {
                LOGGER.warn("Unable to read animation sound timing {}: {}", key, exception.getMessage());
                throw new CompletionException(exception);
            }
        }, Util.nonCriticalIoPool()));
    }

    static void clear() {
        FRAMES.values().forEach(future -> future.cancel(false));
        FRAMES.clear();
    }
}
