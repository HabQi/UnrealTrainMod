/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.google.gson.JsonObject;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.vehicle.VehicleRuntimeState;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.util.GsonHelper;
import org.slf4j.LoggerFactory;

/** Authored object lighting, independent of both geometry ownership and locomotive power. */
public record IrLightingDefinition(float interiorLevel, Map<String, Rule> objects) {
    public static final IrLightingDefinition NONE = new IrLightingDefinition(0.4f, Map.of());
    private static final Pattern HEAD = Pattern.compile("^HEADLIGHT_(?:(?:BOGEY_)?(?:FRONT|REAR)(?:_LOCOMOTIVE)?_)?\\d+(?=_|$)");
    private static final Pattern COLOR = Pattern.compile("(?:^|_)0[xX]([0-9a-fA-F]{6})(?=_|$)");

    public IrLightingDefinition { objects = Map.copyOf(objects); }

    public record Flare(PackageAsset texture, boolean enabled, TrackVector center, float size,
                        int direction, int color, Integer reverseColor, double interval, double offset,
                        boolean blinkFullBright) {
        public boolean on(double ticks) {
            return interval == 0 || ((ticks / 20 + offset) % interval + interval) % interval < interval / 2;
        }
    }

    public record Rule(String group, boolean knownGroup, boolean invert, boolean emissive,
                       boolean interior, Flare flare) {
        public float strength(IrVehicleDefinition definition, Map<String, Float> values,
                              VehicleRuntimeState runtime, boolean front, boolean rear) {
            if (!knownGroup) return 0;
            float value = group == null ? 1 : definition.value(group, values, runtime, front, rear);
            return Math.clamp(invert ? 1 - value : value, 0, 1);
        }
    }

    static IrLightingDefinition read(JsonObject json, Map<String, ModelBounds> parts, float scale,
                                      Map<String, IrControlDefinition> controls, PackageArchive archive, String prefix) {
        var logger = LoggerFactory.getLogger(IrLightingDefinition.class);
        float interior = 0.4f;
        var properties = GsonHelper.getAsJsonObject(json, "properties", new JsonObject());
        if (properties.has("interior_light_level")) {
            try { interior = unit(GsonHelper.getAsFloat(properties, "interior_light_level")); }
            catch (RuntimeException exception) { logger.warn("IR {}: invalid interior_light_level; using 0.4", archive.path()); }
        }
        var lights = new JsonObject();
        if (json.has("lights")) {
            if (json.get("lights").isJsonObject()) lights = json.getAsJsonObject("lights");
            else logger.warn("IR {}: lights must be an object; ignoring flare configuration", archive.path());
        }
        var result = new LinkedHashMap<String, Rule>();
        var unknown = new java.util.HashSet<String>();
        for (var entry : parts.entrySet()) {
            String name = entry.getKey();
            var head = HEAD.matcher(name);
            boolean isHead = head.find();
            String group = IrModelComponents.attribute(name, "LCG");
            boolean full = IrModelComponents.token(name, "FULLBRIGHT");
            boolean inside = IrModelComponents.token(name, "INTERIOR");
            if (!isHead && group == null && !full && !inside) continue;
            boolean known = group == null || controls.containsKey(group);
            if (!known && unknown.add(group)) logger.warn("IR {} {}: unknown light control group '{}'", archive.path(),
                    GsonHelper.getAsString(json, "name"), group);
            Flare flare = null;
            if (isHead) {
                var bounds = entry.getValue();
                var center = new TrackVector(bounds.centerZ() * scale, bounds.centerY() * scale, -bounds.centerX() * scale);
                float size = Math.max(0.05f, Math.max(bounds.length(), bounds.height()) * scale);
                int color = 0xffffff;
                var hex = COLOR.matcher(name);
                if (hex.find()) color = Integer.parseInt(hex.group(1), 16);
                int direction = center.z() >= 0 ? 1 : -1;
                var data = lights.get(name);
                if (data == null) data = lights.get(head.group());
                try {
                    var config = data == null ? new JsonObject() : GsonHelper.convertToJsonObject(data, name);
                    if (GsonHelper.getAsBoolean(config, "revertDirection", false)) direction = -direction;
                    Integer reverse = config.has("reverseColor") ? color(GsonHelper.getAsString(config, "reverseColor")) : null;
                    double interval = number(config, "blinkIntervalSeconds", 0), offset = number(config, "blinkOffsetSeconds", 0);
                    if (interval < 0) throw new IllegalArgumentException("negative blink interval");
                    PackageAsset texture = null;
                    boolean enabled = true;
                    if (config.has("texture")) {
                        String reference = GsonHelper.getAsString(config, "texture");
                        try { texture = archive.resolve(prefix, reference.contains(":") ? reference : "immersiverailroading:" + reference); }
                        catch (IOException exception) {
                            enabled = false;
                            logger.warn("IR {} light {}: disabling flare: {}", archive.path(), name, exception.getMessage());
                        }
                    }
                    flare = new Flare(texture, enabled, center, size, direction, color, reverse, interval, offset,
                            GsonHelper.getAsBoolean(config, "blinkFullBright", true));
                } catch (RuntimeException exception) {
                    logger.warn("IR {} light {}: invalid flare configuration: {}", archive.path(), name, exception.getMessage());
                }
            }
            result.put(name, new Rule(group, known, IrModelComponents.token(name, "LINVERT"), full || isHead, inside, flare));
        }
        return new IrLightingDefinition(interior, result);
    }

    private static float unit(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("expected 0..1");
        return value;
    }
    private static double number(JsonObject json, String key, double fallback) {
        double value = GsonHelper.getAsDouble(json, key, fallback);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite " + key);
        return value;
    }
    private static int color(String text) {
        if (!text.matches("0[xX][0-9a-fA-F]{6}")) throw new IllegalArgumentException("expected 0xRRGGBB");
        return Integer.parseInt(text.substring(2), 16);
    }
}
