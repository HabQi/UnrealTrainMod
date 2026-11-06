/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies local, startup-only walking-area overrides before the vehicle catalog is published. */
public final class VehicleInteriorOverrides {
    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleInteriorOverrides.class);
    /** The first sample uses the provisional bounds calibration, so no measured floor is required yet. */
    private static final String DEFAULT_FILE = """
            {
              "_comment": "Per-vehicle walking area. center=[x,y,z] in model blocks, y is the floor. length/width are full sizes. auto=true derives the rectangle from the authored bounds inset by 0.5.",
              "unrealtrainmod:rtm/9e6b69e9efa972e67597a540ce9247cc4b2c787e585a475ab338be1b5c910b7e": { "auto": true }
            }
            """;

    private VehicleInteriorOverrides() {}

    public static void apply(Path file, Map<ResourceLocation, VehicleDefinition> vehicles) {
        JsonObject json;
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_FILE, StandardCharsets.UTF_8);
                json = GsonHelper.parse(DEFAULT_FILE);
            } else {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    json = GsonHelper.parse(reader);
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Unable to read vehicle interior overrides {}; using package defaults: {}",
                    file, exception.getMessage(), exception);
            return;
        }
        for (var entry : json.entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            var id = ResourceLocation.tryParse(entry.getKey());
            var definition = vehicles.get(id);
            if (definition == null) {
                LOGGER.warn("{}: vehicle interior override references unregistered model {}", file, entry.getKey());
                continue;
            }
            try {
                if (!entry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException("override must be an object");
                }
                var spec = read(entry.getValue().getAsJsonObject(), definition);
                vehicles.put(id, definition.withInteriorSpec(spec));
            } catch (RuntimeException exception) {
                LOGGER.error("{}: invalid vehicle interior override for {}; keeping package defaults: {}",
                        file, id, exception.getMessage());
            }
        }
    }

    private static VehicleInteriorSpec read(JsonObject json, VehicleDefinition definition) {
        for (var key : json.keySet()) {
            if (!Set.of("enabled", "auto", "floor", "center", "length", "width").contains(key)) {
                LOGGER.warn("vehicle interior override for {} has unknown field {}", definition.id(), key);
            }
        }
        if (json.has("enabled") && !GsonHelper.getAsBoolean(json, "enabled", true)) {
            return VehicleInteriorSpec.DISABLED;
        }
        double floor = GsonHelper.getAsDouble(json, "floor", definition.interiorSpec().centerY());
        if (json.has("auto") && GsonHelper.getAsBoolean(json, "auto", false)) {
            return VehicleInteriorSpec.fromBounds(definition.bounds(), floor);
        }
        var centerElement = json.get("center");
        if (centerElement == null || !centerElement.isJsonArray() || centerElement.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException("center must contain three finite XYZ values");
        }
        var center = centerElement.getAsJsonArray();
        double x = number(center.get(0), "center[0]");
        double y = number(center.get(1), "center[1]");
        double z = number(center.get(2), "center[2]");
        double length = number(json.get("length"), "length");
        double width = number(json.get("width"), "width");
        return new VehicleInteriorSpec(true, x, y, z, length, width);
    }

    private static double number(com.google.gson.JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
        return value;
    }
}
