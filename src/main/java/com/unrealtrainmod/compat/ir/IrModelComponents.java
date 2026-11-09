/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Independently interprets IR names and elongated rotation markers during the full OBJ import. */
public final class IrModelComponents {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrModelComponents.class);
    private static final Pattern CONTROL = Pattern.compile("^(THROTTLE_BRAKE|INDEPENDENT_BRAKE|IND_BRAKE|TRAIN_BRAKE|"
            + "THROTTLE|REVERSER|DOOR|WINDOW|WIDGET|BELL_CONTROL|WHISTLE_CONTROL|HORN_CONTROL|ENGINE_START|"
            + "COUPLER_ENGAGED|CYLINDER_DRAIN_CONTROL|TENDER_FEED)_"
            + "(?:(BOGEY_FRONT|BOGEY_REAR|FRONT_LOCOMOTIVE|REAR_LOCOMOTIVE|FRONT|REAR)_)?(\\d+)(?=_|$)");
    private static final Pattern WHEEL = Pattern.compile("^(BOGEY_(FRONT|REAR)_WHEEL|FRAME_WHEEL|WHEEL_DRIVER)_"
            + "(?:(FRONT|REAR)_)?(\\d+)(?=_|$)");
    private static final Pattern GAUGE = Pattern.compile("^(?:GAUGE_(?:SPEED|THROTTLE|REVERSER|TRAIN_BRAKE|"
            + "INDEPENDENT_BRAKE|IND_BRAKE|TEMPERATURE)|BRAKE_PRESSURE|COUPLED)_"
            + "(?:(BOGEY_FRONT|BOGEY_REAR|FRONT|REAR)_)?\\d+(?=_|$)");
    private static final Pattern OFFSET = Pattern.compile("_OFFSET_([^_]+)");
    private static final Pattern ROTATION = Pattern.compile("_ROT_([^_]+)");
    private static final Pattern TRANSLATION = Pattern.compile("_TL_([^_]+)_([XYZ])(?=_|$)");
    private static final Pattern RANGE = Pattern.compile("_RANGE_([^_]+)_([^_]+)");
    private static final TrackVector ZERO = new TrackVector(0, 0, 0);

    private IrModelComponents() {}

    public static boolean isControl(String name) { return CONTROL.matcher(name).find(); }

    public static boolean isReadout(String name) { return GAUGE.matcher(name).find(); }

    public static String controlKey(String name) {
        var matcher = CONTROL.matcher(name);
        return matcher.find() ? matcher.group() : null;
    }

    public static String attribute(String name, String token) {
        var matcher = Pattern.compile("_" + token + "_([^_]+)").matcher(name);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Names with positioned controls belong to their bogie even when BOGEY is not the first token. */
    public static VehiclePartRole role(String name) {
        if (name.startsWith("HEADLIGHT_BOGEY_FRONT_")) return VehiclePartRole.FRONT_BOGIE;
        if (name.startsWith("HEADLIGHT_BOGEY_REAR_")) return VehiclePartRole.REAR_BOGIE;
        var control = CONTROL.matcher(name);
        if (control.find()) {
            if ("BOGEY_FRONT".equals(control.group(2))) return VehiclePartRole.FRONT_BOGIE;
            if ("BOGEY_REAR".equals(control.group(2))) return VehiclePartRole.REAR_BOGIE;
        }
        var gauge = GAUGE.matcher(name);
        if (gauge.find()) {
            if ("BOGEY_FRONT".equals(gauge.group(1))) return VehiclePartRole.FRONT_BOGIE;
            if ("BOGEY_REAR".equals(gauge.group(1))) return VehiclePartRole.REAR_BOGIE;
        }
        return IrBogieGroups.role(name);
    }

    public static List<IrModelComponent> read(ModelData model, float scale, String source) {
        var groups = new LinkedHashMap<String, List<ModelData.Part>>();
        for (var part : model.parts()) {
            if (!part.visible() || part.positions().isEmpty()) continue;
            var wheel = WHEEL.matcher(part.name());
            var control = CONTROL.matcher(part.name());
            var gauge = GAUGE.matcher(part.name());
            String key;
            if (wheel.find()) key = wheel.group();
            else if (control.find()) key = control.group();
            else if (gauge.find()) key = gauge.group();
            else continue;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(part);
        }
        var result = new ArrayList<IrModelComponent>();
        groups.forEach((key, parts) -> {
            try {
                var component = read(key, parts, scale, source);
                if (component != null) result.add(component);
            } catch (NumberFormatException exception) {
                // Invalid authored numeric tokens affect this control, not the entire vehicle.
                LOGGER.warn("IR {} control {} has an invalid numeric token; keeping its static geometry", source, key);
            }
        });
        return List.copyOf(result);
    }

    private static IrModelComponent read(String key, List<ModelData.Part> parts, float scale, String source) {
        var names = parts.stream().map(ModelData.Part::name).distinct().toList();
        var role = role(names.getFirst());
        var kind = WHEEL.matcher(key).find() ? IrModelComponent.Kind.WHEEL
                : GAUGE.matcher(key).find() ? IrModelComponent.Kind.GAUGE
                : key.startsWith("THROTTLE_BRAKE_") ? IrModelComponent.Kind.CENTERED
                : key.startsWith("THROTTLE_") ? IrModelComponent.Kind.THROTTLE
                : key.startsWith("TRAIN_BRAKE_") ? IrModelComponent.Kind.TRAIN_BRAKE
                : key.startsWith("REVERSER_") ? IrModelComponent.Kind.REVERSER : IrModelComponent.Kind.CONTROL;
        var bounds = new ModelBounds.Builder();
        for (var part : parts) for (var p : part.positions()) bounds.include(p.x(), p.y(), p.z());
        var box = bounds.build();
        var pivot = new TrackVector((box.minX() + box.maxX()) * scale / 2,
                (box.minY() + box.maxY()) * scale / 2, (box.minZ() + box.maxZ()) * scale / 2);
        if (kind == IrModelComponent.Kind.WHEEL) {
            float diameter = box.height() * scale;
            if (diameter <= 0) {
                LOGGER.warn("IR {} wheel {} has no diameter; keeping its static geometry", source, key);
                return null;
            }
            return new IrModelComponent(key, role, kind, names, key, pivot, new TrackVector(1, 0, 0),
                    0, ZERO, 0, false, false, diameter, null, 0, 1);
        }
        String group = names.stream().sorted().map(name -> attribute(name, "CG"))
                .filter(java.util.Objects::nonNull).findFirst().orElse(key);
        float offset = 0, degrees = 0, rangeMin = 0, rangeMax = 1;
        boolean invert = false, hide = false, wantsRotation = false;
        double tx = 0, ty = 0, tz = 0;
        TrackVector axis = ZERO;
        ModelData.Part marker = null;
        for (var part : parts) {
            String name = part.name();
            var off = OFFSET.matcher(name);
            if (off.find()) offset = number(off.group(1));
            var range = RANGE.matcher(name);
            if (range.find()) { rangeMin = number(range.group(1)); rangeMax = number(range.group(2)); }
            invert |= token(name, "INVERT");
            hide |= token(name, "HIDE");
            wantsRotation |= token(name, "ROT");
            var rot = ROTATION.matcher(name);
            if (marker == null && rot.find()) {
                marker = part;
                degrees = number(rot.group(1));
            }
            var tl = TRANSLATION.matcher(name);
            while (tl.find()) {
                double amount = number(tl.group(1)) * scale;
                // IR source X/Y/Z maps to imported -Z/Y/X.
                switch (tl.group(2)) {
                    case "X" -> tz = -amount;
                    case "Y" -> ty = amount;
                    case "Z" -> tx = amount;
                }
            }
        }
        if (marker != null) {
            var markerBounds = new ModelBounds.Builder();
            for (var p : marker.positions()) markerBounds.include(p.x(), p.y(), p.z());
            var b = markerBounds.build();
            pivot = new TrackVector((b.minX() + b.maxX()) * scale / 2,
                    (b.minY() + b.maxY()) * scale / 2, (b.minZ() + b.maxZ()) * scale / 2);
            axis = longitudinalAxis(marker.positions(), b);
        }
        if (wantsRotation && axis.equals(ZERO)) {
            LOGGER.warn("IR {} control {} has no valid rotation marker; leaving its transform stationary", source, key);
            degrees = 0;
            tx = ty = tz = 0;
        }
        if (!wantsRotation && tx == 0 && ty == 0 && tz == 0 && !hide
                && kind != IrModelComponent.Kind.THROTTLE && kind != IrModelComponent.Kind.TRAIN_BRAKE
                && kind != IrModelComponent.Kind.REVERSER && kind != IrModelComponent.Kind.GAUGE) return null;
        if (rangeMin == rangeMax) throw new NumberFormatException("Empty gauge range");
        IrReadout readout = kind != IrModelComponent.Kind.GAUGE ? null
                : key.startsWith("GAUGE_SPEED_") ? IrReadout.SPEED
                : key.startsWith("GAUGE_THROTTLE_") ? IrReadout.THROTTLE
                : key.startsWith("GAUGE_REVERSER_") ? IrReadout.REVERSER
                : key.startsWith("GAUGE_TRAIN_BRAKE_") ? IrReadout.TRAIN_BRAKE
                : key.startsWith("COUPLED_FRONT_") ? IrReadout.COUPLED_FRONT
                : key.startsWith("COUPLED_REAR_") ? IrReadout.COUPLED_REAR : IrReadout.UNAVAILABLE;
        return new IrModelComponent(key, role, kind, names, group, pivot, axis, degrees,
                new TrackVector(tx, ty, tz), offset, invert, hide, 0, readout, rangeMin, rangeMax);
    }

    /** End-cloud centroids give the marker's long axis; orientation is relative to the authored origin. */
    private static TrackVector longitudinalAxis(List<ModelData.Position> points, ModelBounds bounds) {
        double cx = (bounds.minX() + bounds.maxX()) / 2.0;
        double cy = (bounds.minY() + bounds.maxY()) / 2.0;
        double cz = (bounds.minZ() + bounds.maxZ()) / 2.0;
        var a = points.getFirst();
        for (var p : points) if (distance(p, cx, cy, cz) > distance(a, cx, cy, cz)) a = p;
        var b = a;
        for (var p : points) if (distance(p, a.x(), a.y(), a.z()) > distance(b, a.x(), a.y(), a.z())) b = p;
        if (distance(a, 0, 0, 0) > distance(b, 0, 0, 0)) {
            var swap = a; a = b; b = swap;
        }
        double ax = 0, ay = 0, az = 0, bx = 0, by = 0, bz = 0;
        int na = 0, nb = 0;
        for (var p : points) {
            double da = distance(p, a.x(), a.y(), a.z()), db = distance(p, b.x(), b.y(), b.z());
            if (da < db) { ax += p.x(); ay += p.y(); az += p.z(); na++; }
            else if (db < da) { bx += p.x(); by += p.y(); bz += p.z(); nb++; }
        }
        if (na == 0 || nb == 0) return ZERO;
        double x = bx / nb - ax / na, y = by / nb - ay / na, z = bz / nb - az / na;
        double length = Math.sqrt(x * x + y * y + z * z);
        return length == 0 ? ZERO : new TrackVector(x / length, y / length, z / length);
    }

    private static double distance(ModelData.Position p, double x, double y, double z) {
        double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static float number(String text) {
        float value = Float.parseFloat(text);
        if (!Float.isFinite(value)) throw new NumberFormatException("Non-finite IR control value");
        return value;
    }

    public static boolean token(String name, String token) {
        return name.startsWith(token + "_") || name.endsWith("_" + token) || name.contains("_" + token + "_");
    }
}
