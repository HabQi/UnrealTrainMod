/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm;

import com.google.gson.JsonObject;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional selection artwork must not prevent a model from being registered. */
final class RtmButtonTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtmButtonTexture.class);

    private RtmButtonTexture() {}

    static PackageAsset read(JsonObject json, PackageArchive archive, String prefix, PackageAsset config) {
        try {
            var value = json.get("buttonTexture");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IOException("buttonTexture is missing or is not a string");
            }
            return archive.resolve(prefix, value.getAsString());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("RTM {}!{}: buttonTexture unavailable; using text entry: {}",
                    config.archive(), config.entry(), exception.getMessage());
            return null;
        }
    }
}
