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
import com.unrealtrainmod.track.geometry.TrackVector;
import java.io.IOException;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bad sound never rejects the vehicle or its animation. */
final class IrSoundDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrSoundDefinitions.class);

    private IrSoundDefinitions() {}

    record Binding(String group, IrReadout driver, TrackVector position) {}

    static IrVehicleSounds read(JsonObject json, PackageArchive archive, String prefix,
                                 Set<String> engines, Set<String> horns, Set<String> bells, Map<String, Binding> bindings) {
        String label = archive.path() + " " + GsonHelper.getAsString(json, "name");
        float ridingVolume = 0.75f;
        try {
            ridingVolume = GsonHelper.getAsFloat(json, "sound_dampening_percentage", ridingVolume);
            if (!Float.isFinite(ridingVolume) || ridingVolume < 0 || ridingVolume > 1) {
                throw new IllegalArgumentException("expected a finite value within 0..1");
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("IR {} sound_dampening_percentage: {}; using 0.75", label, exception.getMessage());
            ridingVolume = 0.75f;
        }
        var sounds = new JsonObject();
        var element = json.get("sounds");
        if (element != null && element.isJsonObject()) sounds = element.getAsJsonObject();
        else if (element != null && !element.isJsonNull()) {
            LOGGER.warn("IR {} in {}: sounds must be an object; ignoring vehicle sounds",
                    GsonHelper.getAsString(json, "name"), archive.path());
        }
        var propertyData = json.get("properties");
        var properties = propertyData != null && propertyData.isJsonObject() ? propertyData.getAsJsonObject() : new JsonObject();
        float pitchRange = 0.25f;
        try {
            var pitch = sounds.get("engine_pitch_range");
            if (pitch != null) {
                if (!pitch.isJsonPrimitive() || !pitch.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("expected a number");
                }
                float value = pitch.getAsFloat();
                if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("expected a finite value within 0..1");
                pitchRange = value;
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("IR {} sounds.engine_pitch_range: {}; using 0.25", label, exception.getMessage());
        }
        return new IrVehicleSounds(read(sounds.get("wheels"), archive, prefix, label + " sounds.wheels", 32),
                read(sounds.get("idle"), archive, prefix, label + " sounds.idle", 80),
                read(sounds.get("running"), archive, prefix, label + " sounds.running", 80),
                read(sounds.get("horn"), archive, prefix, label + " sounds.horn", 128),
                read(sounds.get("bell"), archive, prefix, label + " sounds.bell", 128),
                read(sounds.get("couple"), archive, prefix, label + " sounds.couple", 40), engines, horns, bells,
                controls(sounds.get("controls"), archive, prefix, label, bindings), ridingVolume,
                flag(properties, "horn_sustained", false, label), flag(properties, "toggle_bell", true, label), pitchRange);
    }

    private static boolean flag(JsonObject properties, String key, boolean fallback, String label) {
        var value = properties.get(key);
        if (value == null) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        LOGGER.warn("IR {} properties.{}: expected a boolean; using {}", label, key, fallback);
        return fallback;
    }

    private static Map<String, IrControlSound> controls(JsonElement element, PackageArchive archive, String prefix,
                                                       String label, Map<String, Binding> bindings) {
        if (element == null || element.isJsonNull()) return Map.of();
        if (!element.isJsonObject()) {
            LOGGER.warn("IR {} sounds.controls: expected an object", label);
            return Map.of();
        }
        var result = new LinkedHashMap<String, IrControlSound>();
        for (var entry : element.getAsJsonObject().entrySet()) {
            try {
                var binding = bindings.get(entry.getKey());
                if (binding == null) throw new IOException("no matching OBJ control component");
                var json = GsonHelper.convertToJsonObject(entry.getValue(), entry.getKey());
                var engage = controlClip(json, "engage", archive, prefix, label, entry.getKey());
                var move = controlClip(json, "move", archive, prefix, label, entry.getKey());
                var disengage = controlClip(json, "disengage", archive, prefix, label, entry.getKey());
                if (engage == null && move == null && disengage == null) continue;
                Float percent = json.has("movePercent") ? GsonHelper.getAsFloat(json, "movePercent") : null;
                if (percent != null && (!Float.isFinite(percent) || percent < 0 || percent > 1)) {
                    throw new IOException("movePercent must be within 0..1");
                }
                result.put(entry.getKey(), new IrControlSound(binding.group(), binding.driver(), binding.position(),
                        engage, move, disengage, percent));
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("IR {} sounds.controls.{}: ignoring control sound: {}",
                        label, entry.getKey(), exception.getMessage());
            }
        }
        return result;
    }

    private static PackageAsset controlClip(JsonObject json, String field, PackageArchive archive, String prefix,
                                             String label, String key) {
        try {
            return clip(json, field, archive, prefix);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("IR {} sounds.controls.{}.{}: ignoring clip: {}", label, key, field, exception.getMessage());
            return null;
        }
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
