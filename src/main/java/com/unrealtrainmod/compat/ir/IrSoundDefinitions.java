/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.sound.VehicleSoundSequence;
import java.io.IOException;
import java.util.Set;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bad sound never rejects the vehicle or its animation. */
final class IrSoundDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrSoundDefinitions.class);

    private IrSoundDefinitions() {}

    static IrVehicleSounds read(JsonObject json, PackageArchive archive, String prefix,
                                 Set<String> engines, Set<String> horns) {
        if (!json.has("sounds") || json.get("sounds").isJsonNull()) return IrVehicleSounds.NONE;
        if (!json.get("sounds").isJsonObject()) {
            LOGGER.warn("IR {} in {}: sounds must be an object; ignoring vehicle sounds",
                    GsonHelper.getAsString(json, "name"), archive.path());
            return IrVehicleSounds.NONE;
        }
        var sounds = json.getAsJsonObject("sounds");
        String label = archive.path() + " " + GsonHelper.getAsString(json, "name");
        return new IrVehicleSounds(read(sounds.get("wheels"), archive, prefix, label + " sounds.wheels", 32),
                read(sounds.get("idle"), archive, prefix, label + " sounds.idle", 80),
                read(sounds.get("running"), archive, prefix, label + " sounds.running", 80),
                read(sounds.get("horn"), archive, prefix, label + " sounds.horn", 128), engines, horns);
    }

    static VehicleSoundSequence read(JsonElement element, PackageArchive archive, String prefix,
                                      String label, int defaultDistance) {
        if (element == null || element.isJsonNull()) return null;
        try {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                return new VehicleSoundSequence(null, clip(element.getAsString(), archive, prefix), null,
                        true, 1, defaultDistance);
            }
            if (!element.isJsonObject()) throw new IOException("expected an OGG path or sound object");
            var json = element.getAsJsonObject();
            var start = clip(json, "start", archive, prefix);
            var main = clip(json, "main", archive, prefix);
            var stop = clip(json, "stop", archive, prefix);
            if (start == null && main == null && stop == null) throw new IOException("sound object has no clips");
            float volume = GsonHelper.getAsFloat(json, "volume", 1);
            double distance = GsonHelper.getAsDouble(json, "distance", defaultDistance);
            if (!Float.isFinite(volume) || volume < 0) throw new IOException("volume must be finite and nonnegative");
            if (!Double.isFinite(distance) || distance <= 0 || distance > Integer.MAX_VALUE) {
                throw new IOException("distance must be finite, positive and within integer range");
            }
            return new VehicleSoundSequence(start, main, stop, GsonHelper.getAsBoolean(json, "looping", true),
                    volume, (int)Math.ceil(distance));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("IR {}: ignoring sound: {}", label, exception.getMessage());
            return null;
        }
    }

    private static PackageAsset clip(JsonObject json, String field, PackageArchive archive, String prefix)
            throws IOException {
        if (!json.has(field) || json.get(field).isJsonNull()) return null;
        return clip(GsonHelper.getAsString(json, field), archive, prefix);
    }

    private static PackageAsset clip(String reference, PackageArchive archive, String prefix) throws IOException {
        if (!reference.endsWith(".ogg")) throw new IOException("only direct .ogg references are supported: " + reference);
        return archive.resolve(prefix, reference.indexOf(':') < 0 ? "immersiverailroading:" + reference : reference);
    }
}
