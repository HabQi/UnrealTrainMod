/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm;

import com.google.gson.JsonObject;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.MqoModelInput;
import com.unrealtrainmod.model.MqoParser;
import com.unrealtrainmod.resource.LoadReport;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.resource.cache.ModelCache;
import com.unrealtrainmod.resource.cache.ModelCacheCodecs;
import com.unrealtrainmod.wiring.WiringDefinition;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;

final class RtmWiringAdapter {
    private final Map<ResourceLocation, WiringDefinition> definitions;
    private final LoadReport report;
    private final ModelCache cache;

    RtmWiringAdapter(Map<ResourceLocation, WiringDefinition> definitions, LoadReport report, ModelCache cache) {
        this.definitions = definitions; this.report = report; this.cache = cache;
    }

    void read(PackageArchive archive, String entry, String prefix, boolean wire) {
        var config = new PackageAsset(archive.path(), entry);
        var kind = wire ? LoadReport.Kind.RTM_WIRE : LoadReport.Kind.RTM_CONNECTOR;
        try {
            JsonObject json;
            try (var reader = new InputStreamReader(archive.open(entry), StandardCharsets.UTF_8)) {
                json = GsonHelper.parse(reader);
            }
            String name = GsonHelper.getAsString(json, "name");
            if (name.isBlank()) throw new IOException("name must not be blank");
            var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID,
                    "rtm/" + (wire ? "wire/" : "connector/") + ResourceIds.hash(name));
            var previous = definitions.get(id);
            if (previous != null) { report.duplicate(kind, id, config, previous.config()); return; }
            var model = GsonHelper.getAsJsonObject(json, "model");
            String file = GsonHelper.getAsString(model, "modelFile");
            if (!MqoModelInput.isSupported(file)) throw new IOException("Unsupported model.modelFile: " + file);
            var asset = archive.resolve(prefix, "models/" + file);
            var textures = new LinkedHashMap<String, WiringDefinition.Texture>();
            if (model.has("textures")) for (var value : GsonHelper.getAsJsonArray(model, "textures")) {
                var binding = value.getAsJsonArray();
                if (binding.size() < 2 || binding.size() > 3) throw new IOException("Invalid model.textures binding");
                String material = binding.get(0).getAsString();
                var flags = Arrays.stream((binding.size() == 3 ? binding.get(2).getAsString() : "").split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).toList();
                var texture = new WiringDefinition.Texture(archive.resolve(prefix, binding.get(1).getAsString()), flags);
                if (textures.putIfAbsent(material, texture) != null) throw new IOException("Duplicate material: " + material);
            }
            String script = GsonHelper.getAsString(model, "rendererPath", "");
            var renderer = script.isBlank() ? null : archive.resolve(prefix, script);
            Vec3 point = Vec3.ZERO;
            if (!wire && json.has("wirePos")) {
                var a = GsonHelper.getAsJsonArray(json, "wirePos");
                if (a.size() != 3) throw new IOException("wirePos must have three coordinates");
                point = new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
                if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
                    throw new IOException("wirePos must be finite");
                }
            }
            double section = number(json, "sectionLength", 1), deflection = number(json, "deflectionCoefficient", 0);
            double length = number(json, "lengthCoefficient", 0), yOffset = number(json, "yOffset", 0);
            if (section <= 0 || deflection < 0 || length < 0) throw new IOException("Invalid wire length/deflection parameters");
            var data = cache.load(asset, "source", "rtm-wiring-editor-metadata-v1", ModelCacheCodecs.MODEL_DATA, () -> {
                try (var input = MqoModelInput.open(archive.open(asset.entry()), asset.entry())) {
                    return MqoParser.parseInstalledObject(input, StandardCharsets.UTF_8);
                }
            });
            var bounds = new ModelBounds.Builder();
            for (var part : data.parts()) if (part.visible()) for (var p : part.positions()) {
                bounds.include(p.x() * 0.01f, p.y() * 0.01f, p.z() * 0.01f);
            }
            ModelBounds box = bounds.build();
            definitions.put(id, new WiringDefinition(id, name, wire, config,
                    RtmButtonTexture.read(json, archive, prefix, config), asset, data, textures, renderer,
                    GsonHelper.getAsBoolean(json, "smoothing", true), GsonHelper.getAsBoolean(json, "doCulling", true),
                    GsonHelper.getAsString(json, "tags", ""), box == null ? new ModelBounds(-.125f, -.125f, -.125f, .125f, .125f, .125f) : box,
                    point, GsonHelper.getAsString(json, "connectorType", "Relay"), section, deflection, length, yOffset,
                    GsonHelper.getAsBoolean(json, "useCustomColor", false)));
            report.registered(kind);
        } catch (IOException | RuntimeException e) { report.failed(kind, config, e); }
    }

    private static double number(JsonObject json, String key, double fallback) throws IOException {
        double value = GsonHelper.getAsDouble(json, key, fallback);
        if (!Double.isFinite(value)) throw new IOException(key + " must be finite");
        return value;
    }
}
