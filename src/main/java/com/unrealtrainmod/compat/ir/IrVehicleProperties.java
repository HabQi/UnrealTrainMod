/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.google.gson.JsonObject;
import com.unrealtrainmod.vehicle.VehiclePerformanceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Import authored performance in SI units, independently of model and gauge scaling. */
record IrVehicleProperties(VehiclePerformanceSpec performance, int throttleNotches) {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrVehicleProperties.class);

    static IrVehicleProperties read(JsonObject json, String type, String label) {
        var properties = new JsonObject();
        var element = json.get("properties");
        if (element != null && element.isJsonObject()) properties = element.getAsJsonObject();
        else if (element != null) LOGGER.warn("IR {}: properties must be an object; using defaults", label);
        var base = VehiclePerformanceSpec.DEFAULT;
        boolean locomotive = type.equals("locomotives");
        double mass = number(properties, "weight_kg", base.mass(), 1, true, label);
        if (!Double.isFinite(mass * 1.5)) {
            LOGGER.warn("IR {} properties.weight_kg: derived brake force overflows; using {}", label, base.mass());
            mass = base.mass();
        }
        var performance = new VehiclePerformanceSpec(mass,
                number(properties, "max_speed_kmh", base.maxSpeed(), 1 / 3.6, true, label),
                number(properties, "tractive_effort_lbf", locomotive ? base.maxTractiveEffort() : 0,
                        4.4482216152605, false, label),
                mass, mass * 1.5, base.rollingResistance(),
                number(properties, "horsepower", locomotive ? base.ratedPower() : 0,
                        745.6998715822702, false, label),
                base.dragCoefficient(), base.serviceBrakeApplyTime(), base.emergencyBrakeApplyTime(), base.brakeReleaseTime());
        return new IrVehicleProperties(performance, notches(properties, label));
    }

    private static double number(JsonObject properties, String field, double fallback, double factor,
                                  boolean positive, String label) {
        var element = properties.get(field);
        if (element == null) return fallback;
        try {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("expected a number");
            }
            double value = element.getAsDouble() * factor;
            if (!Double.isFinite(value) || (positive ? value <= 0 : value < 0)) {
                throw new IllegalArgumentException(positive ? "expected a finite positive value" : "expected a finite nonnegative value");
            }
            return value;
        } catch (RuntimeException exception) {
            LOGGER.warn("IR {} properties.{}: {}; using {} in SI units", label, field, exception.getMessage(), fallback);
            return fallback;
        }
    }

    private static int notches(JsonObject properties, String label) {
        var element = properties.get("throttle_notches");
        if (element == null) return 8;
        try {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("expected a number");
            }
            int value = element.getAsBigDecimal().intValueExact();
            if (value < 1 || value > 64) throw new IllegalArgumentException("outside 1..64");
            return value;
        } catch (RuntimeException exception) {
            LOGGER.warn("IR {} properties.throttle_notches: expected an integer within 1..64; using 8", label);
            return 8;
        }
    }
}
