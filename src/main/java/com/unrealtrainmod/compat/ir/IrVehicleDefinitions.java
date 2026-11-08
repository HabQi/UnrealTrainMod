/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.google.gson.JsonObject;
import com.unrealtrainmod.resource.PackageArchive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds a logical registry from the existing lightweight OBJ scan, without reading faces again. */
final class IrVehicleDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrVehicleDefinitions.class);
    private IrVehicleDefinitions() {}

    static IrVehicleDefinition read(JsonObject json, Set<String> names, PackageArchive archive, String prefix) throws IOException {
        var defaults = new HashMap<String, Float>();
        var configured = GsonHelper.getAsJsonObject(json, "controls", new JsonObject());
        for (var entry : configured.entrySet()) {
            float value = GsonHelper.getAsFloat(GsonHelper.convertToJsonObject(entry.getValue(), entry.getKey()), "default", 0);
            if (!Float.isFinite(value) || value < 0 || value > 1) throw new IOException("Invalid IR default: " + entry.getKey());
            defaults.put(entry.getKey(), value);
        }
        var objects = new LinkedHashMap<String, List<String>>();
        for (String name : new TreeSet<>(names)) {
            String key = IrModelComponents.controlKey(name);
            if (key != null) objects.computeIfAbsent(key, ignored -> new ArrayList<>()).add(name);
        }
        var controls = new LinkedHashMap<String, IrControlDefinition>();
        var engines = new HashSet<String>();
        var horns = new HashSet<String>();
        for (var entry : objects.entrySet()) {
            String key = entry.getKey(), group = null, label = null;
            boolean toggle = false, press = false;
            for (String name : entry.getValue()) {
                String cg = IrModelComponents.attribute(name, "CG");
                if (group == null && cg != null) group = cg;
                String text = IrModelComponents.attribute(name, "LABEL");
                if (label == null && text != null) label = text.replace('^', ' ');
                toggle |= IrModelComponents.token(name, "TOGGLE");
                press |= IrModelComponents.token(name, "PRESS");
            }
            if (group == null) group = key;
            if (key.startsWith("ENGINE_START_")) engines.add(group);
            if (key.startsWith("HORN_CONTROL_")) horns.add(group);
            if (label == null) label = key;
            IrReadout driver = key.startsWith("THROTTLE_") && !key.startsWith("THROTTLE_BRAKE_") ? IrReadout.THROTTLE
                    : key.startsWith("TRAIN_BRAKE_") ? IrReadout.TRAIN_BRAKE
                    : key.startsWith("REVERSER_") ? IrReadout.REVERSER : null;
            float base = key.startsWith("REVERSER_") || key.startsWith("THROTTLE_BRAKE_") ? 0.5f : 0;
            var type = press ? IrControlDefinition.Type.PRESS : toggle ? IrControlDefinition.Type.TOGGLE
                    : IrControlDefinition.Type.CONTINUOUS;
            var control = new IrControlDefinition(group, label, type, defaults.getOrDefault(group, base), driver);
            controls.merge(group, control, IrVehicleDefinitions::merge);
        }
        var animations = new ArrayList<IrAnimationDefinition>();
        if (json.has("animations")) for (var element : GsonHelper.getAsJsonArray(json, "animations")) {
            try {
                var entry = GsonHelper.convertToJsonObject(element, "animation");
                String group = GsonHelper.getAsString(entry, "control_group", null);
                IrReadout readout = group == null ? readout(GsonHelper.getAsString(entry, "readout")) : null;
                String reference = GsonHelper.getAsString(entry, "animatrix");
                if (!reference.contains(":")) reference = "immersiverailroading:" + reference;
                var mode = IrAnimationDefinition.Mode.valueOf(GsonHelper.getAsString(entry, "mode").toUpperCase(Locale.ROOT));
                float rate = number(entry, "frames_per_tick", 1), min = number(entry, "range_min", 0);
                float max = number(entry, "range_max", 1), offset = number(entry, "offset", 0);
                if (rate <= 0 || min == max) throw new IllegalArgumentException("Invalid rate or range");
                var animation = new IrAnimationDefinition(archive.resolve(prefix, reference), group, readout, mode,
                        rate, min, max, offset, GsonHelper.getAsBoolean(entry, "invert", false),
                        IrSoundDefinitions.read(entry.get("sound"), archive, prefix,
                                archive.path() + " " + GsonHelper.getAsString(json, "name")
                                        + " animations[" + animations.size() + "].sound", 32));
                animations.add(animation);
                if (group != null) controls.putIfAbsent(group, new IrControlDefinition(group, group,
                        mode == IrAnimationDefinition.Mode.VALUE ? IrControlDefinition.Type.CONTINUOUS : IrControlDefinition.Type.TOGGLE,
                        defaults.getOrDefault(group, 0f), null));
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("IR {}: skipping invalid animation: {}", GsonHelper.getAsString(json, "name"), exception.getMessage());
            }
        }
        defaults.forEach((group, value) -> controls.putIfAbsent(group,
                new IrControlDefinition(group, group, IrControlDefinition.Type.CONTINUOUS, value, null)));
        float speed = number(GsonHelper.getAsJsonObject(json, "properties", new JsonObject()), "max_speed_kmh", 200);
        if (speed <= 0) throw new IOException("IR max_speed_kmh must be positive");
        return new IrVehicleDefinition(controls, animations, speed,
                IrSoundDefinitions.read(json, archive, prefix, engines, horns));
    }

    private static IrControlDefinition merge(IrControlDefinition a, IrControlDefinition b) {
        var type = a.type() == IrControlDefinition.Type.CONTINUOUS || b.type() == IrControlDefinition.Type.CONTINUOUS
                ? IrControlDefinition.Type.CONTINUOUS
                : a.type() == IrControlDefinition.Type.TOGGLE || b.type() == IrControlDefinition.Type.TOGGLE
                ? IrControlDefinition.Type.TOGGLE : IrControlDefinition.Type.PRESS;
        return new IrControlDefinition(a.group(), a.label(), type, a.defaultValue(), a.driver() != null ? a.driver() : b.driver());
    }

    private static IrReadout readout(String value) {
        try { return IrReadout.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            LOGGER.warn("IR readout {} has no native simulation value; using zero", value);
            return IrReadout.UNAVAILABLE;
        }
    }

    private static float number(JsonObject json, String name, float fallback) {
        float value = GsonHelper.getAsFloat(json, name, fallback);
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite " + name);
        return value;
    }
}
