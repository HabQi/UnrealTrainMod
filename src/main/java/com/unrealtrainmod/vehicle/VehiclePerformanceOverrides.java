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

/** Startup-only local performance overrides, using the same model IDs as motion overrides. */
public final class VehiclePerformanceOverrides {
    private static final Logger LOGGER = LoggerFactory.getLogger(VehiclePerformanceOverrides.class);
    private VehiclePerformanceOverrides() {}

    public static void apply(Path file, Map<ResourceLocation, VehicleDefinition> vehicles) {
        JsonObject json;
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, "{}\n", StandardCharsets.UTF_8);
                return;
            }
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { json = GsonHelper.parse(reader); }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Unable to read vehicle performance overrides {}; keeping defaults", file, exception);
            return;
        }
        for (var entry : json.entrySet()) {
            var id = ResourceLocation.tryParse(entry.getKey());
            var definition = vehicles.get(id);
            if (definition == null) {
                LOGGER.warn("{}: performance override references unregistered model {}", file, entry.getKey());
                continue;
            }
            try {
                if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("override must be an object");
                var value = entry.getValue().getAsJsonObject();
                var base = definition.performanceSpec();
                for (String field : value.keySet()) {
                    switch (field) {
                        case "mass", "maxSpeed", "maxTractiveEffort", "maxServiceBrakeForce",
                                "maxEmergencyBrakeForce", "rollingResistance", "ratedPower", "dragCoefficient",
                                "serviceBrakeApplyTime", "emergencyBrakeApplyTime", "brakeReleaseTime" -> { }
                        default -> LOGGER.warn("{}: performance override for {} has unknown field {}", file, id, field);
                    }
                }
                var spec = new VehiclePerformanceSpec(number(value, "mass", base.mass()),
                        number(value, "maxSpeed", base.maxSpeed()),
                        number(value, "maxTractiveEffort", base.maxTractiveEffort()),
                        number(value, "maxServiceBrakeForce", base.maxServiceBrakeForce()),
                        number(value, "maxEmergencyBrakeForce", base.maxEmergencyBrakeForce()),
                        number(value, "rollingResistance", base.rollingResistance()),
                        number(value, "ratedPower", base.ratedPower()),
                        number(value, "dragCoefficient", base.dragCoefficient()),
                        number(value, "serviceBrakeApplyTime", base.serviceBrakeApplyTime()),
                        number(value, "emergencyBrakeApplyTime", base.emergencyBrakeApplyTime()),
                        number(value, "brakeReleaseTime", base.brakeReleaseTime()));
                vehicles.put(id, definition.withPerformanceSpec(spec));
            } catch (RuntimeException exception) {
                LOGGER.error("{}: invalid performance override for {}; keeping defaults: {}", file, id, exception.getMessage());
            }
        }
    }

    private static double number(JsonObject json, String field, double fallback) {
        if (!json.has(field)) return fallback;
        var value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return value.getAsDouble();
    }
}
