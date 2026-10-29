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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies local, startup-only overrides before the vehicle catalog is published. */
public final class VehicleMotionOverrides {
    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleMotionOverrides.class);

    private VehicleMotionOverrides() {}

    public static void apply(Path file, Map<ResourceLocation, VehicleDefinition> vehicles) {
        JsonObject json;
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, "{}\n", StandardCharsets.UTF_8);
                return;
            }
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                json = GsonHelper.parse(reader);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Unable to read vehicle motion overrides {}; using package defaults: {}",
                    file, exception.getMessage(), exception);
            return;
        }
        for (var entry : json.entrySet()) {
            var id = ResourceLocation.tryParse(entry.getKey());
            var definition = vehicles.get(id);
            if (definition == null) {
                LOGGER.warn("{}: vehicle motion override references unregistered model {}", file, entry.getKey());
                continue;
            }
            try {
                if (!entry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException("override must be an object");
                }
                var spec = read(entry.getValue().getAsJsonObject(), definition.motionSpec(), file, id);
                vehicles.put(id, new VehicleDefinition(definition.id(), definition.name(), definition.config(),
                        definition.buttonTexture(), definition.trainType(), definition.parts(), definition.bounds(), spec));
            } catch (RuntimeException exception) {
                LOGGER.error("{}: invalid vehicle motion override for {}; keeping package defaults: {}",
                        file, id, exception.getMessage());
            }
        }
    }

    private static VehicleMotionSpec read(JsonObject json, VehicleMotionSpec base, Path file, ResourceLocation id) {
        double length = base.length(), width = base.width(), height = base.height(), trackDatum = base.trackDatum();
        double frontCoupler = base.frontCouplerOffset(), rearCoupler = base.rearCouplerOffset();
        double frontBogie = base.frontBogieOffset(), rearBogie = base.rearBogieOffset();
        double gap = base.defaultCouplerGap();
        for (var entry : json.entrySet()) {
            String field = entry.getKey();
            switch (field) {
                case "length" -> length = number(json, field);
                case "width" -> width = number(json, field);
                case "height" -> height = number(json, field);
                case "trackDatum" -> trackDatum = number(json, field);
                case "frontCouplerOffset" -> frontCoupler = number(json, field);
                case "rearCouplerOffset" -> rearCoupler = number(json, field);
                case "frontBogieOffset" -> frontBogie = number(json, field);
                case "rearBogieOffset" -> rearBogie = number(json, field);
                case "defaultCouplerGap" -> gap = number(json, field);
                default -> LOGGER.warn("{}: vehicle motion override for {} has unknown field {}", file, id, field);
            }
        }
        return new VehicleMotionSpec(length, width, height, trackDatum,
                frontCoupler, rearCoupler, frontBogie, rearBogie, gap);
    }

    private static double number(JsonObject json, String field) {
        var value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return value.getAsDouble();
    }
}
