/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.ObjParser;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.TrackGeometryDefinition;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native visual resources; server identities do not depend on client resource packs. */
public final class NativeTurnoutModels extends SimplePreparableReloadListener<NativeTurnoutModels.Model> {
    public static final NativeTurnoutModels INSTANCE = new NativeTurnoutModels();
    public static final ResourceLocation CONFIG = ResourceLocation.parse("unrealtrainmod:native_turnout/default.json");
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/NativeTurnout");
    public record Part(List<Source> sources, Matrix4f offset, Vector3f normal, Vector3f reverse) {}
    public record Model(Map<String, Part> parts, String fingerprint, String failure, double padding) {
        public boolean available() { return failure == null; }
    }
    private record LayoutKey(String style, String generation, String definition, double gauge, List<Double> geometry) {}
    private static final class CachedLayout {
        final NativeTurnoutLayout layout;
        int references;
        CachedLayout(NativeTurnoutLayout layout) { this.layout = layout; }
    }
    final class Lease implements AutoCloseable {
        private final LayoutKey key;
        private final CachedLayout cached;
        private boolean closed;
        Lease(LayoutKey key, CachedLayout cached) { this.key = key; this.cached = cached; cached.references++; }
        NativeTurnoutLayout layout() { return cached.layout; }
        @Override public void close() {
            if (!closed && --cached.references == 0) layouts.remove(key, cached);
            closed = true;
        }
    }
    private final Map<LayoutKey, CachedLayout> layouts = new HashMap<>();
    private Model model;
    private long builds, hits, fallbacks;
    private NativeTurnoutModels() {}
    public Model model() { return model; }

    Lease acquire(TrackAssemblyInstance assembly) {
        var values = new ArrayList<Double>();
        var origin = assembly.origin();
        assembly.geometry().paths().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            values.add((double)entry.getValue().size());
            for (var geometry : entry.getValue()) {
                add(values, geometry.sample(0).position(), origin);
                add(values, geometry.sample(geometry.getLength()).position(), origin);
                var definition = TrackGeometryDefinition.from(geometry);
                values.add((double)definition.controls().size());
                for (var p : definition.controls()) { values.add(p.x() - origin.x()); values.add(p.z() - origin.z()); }
            }
        });
        var key = new LayoutKey(NativeTurnoutCatalog.DEFAULT.toString(),
                model == null ? "missing" : model.fingerprint(), assembly.definition().name(), assembly.gauge(), List.copyOf(values));
        var cached = layouts.get(key);
        if (cached == null) {
            cached = new CachedLayout(NativeTurnoutLayout.create(assembly));
            layouts.put(key, cached); builds++;
        } else hits++;
        if (model == null || !model.available()) fallbacks++;
        return new Lease(key, cached);
    }

    private static void add(List<Double> values, TrackVector p, TrackVector origin) {
        values.add(p.x() - origin.x()); values.add(p.y() - origin.y()); values.add(p.z() - origin.z());
    }

    void collectStats(TrackRenderStats stats) {
        if (stats != null) stats.nativeLayouts(builds, hits, fallbacks, layouts.size());
        builds = hits = fallbacks = 0;
    }

    @Override protected Model prepare(ResourceManager resources, ProfilerFiller profiler) {
        var bytes = new LinkedHashMap<ResourceLocation, byte[]>();
        try {
            var json = GsonHelper.parse(new String(read(resources, CONFIG, bytes), StandardCharsets.UTF_8));
            var configured = GsonHelper.getAsJsonObject(json, "parts");
            var models = new HashMap<ResourceLocation, ModelMesh>();
            var parts = new LinkedHashMap<String, Part>();
            var validatedTextures = new java.util.HashSet<ResourceLocation>();
            double padding = 0;
            for (String role : List.of("sleeper", "switch_machine", "throw_rod")) {
                var config = GsonHelper.getAsJsonObject(configured, role);
                var id = ResourceLocation.parse(GsonHelper.getAsString(config, "model"));
                var mesh = models.get(id);
                if (mesh == null) {
                    try (var reader = new InputStreamReader(new ByteArrayInputStream(read(resources, id, bytes)), StandardCharsets.UTF_8)) {
                        var data = ObjParser.parse(reader, id.getPath(), path -> new InputStreamReader(
                                new ByteArrayInputStream(read(resources, ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path), bytes)), StandardCharsets.UTF_8));
                        mesh = ModelMesh.prepare(data, 1, false);
                        models.put(id, mesh);
                    }
                }
                var names = GsonHelper.getAsJsonArray(config, "objects");
                var selected = new java.util.HashSet<String>();
                for (var name : names) selected.add(name.getAsString());
                var missing = new java.util.HashSet<>(selected);
                var sources = new ArrayList<Source>();
                double radius = 0;
                for (var part : mesh.parts()) if (selected.contains(part.name())) {
                    missing.remove(part.name());
                    for (var batch : part.batches()) {
                        var material = batch.material();
                        if (material.alpha() != 1) throw new IOException(role + ": only opaque/cutout materials are supported");
                        var texture = material.texture() == null ? com.unrealtrainmod.client.model.TextureLoader.WHITE
                                : ResourceLocation.fromNamespaceAndPath(id.getNamespace(), material.texture());
                        if (material.texture() != null && validatedTextures.add(texture)) {
                            try (var image = NativeImage.read(new ByteArrayInputStream(read(resources, texture, bytes)))) {
                                if (image.getWidth() < 1 || image.getHeight() < 1) throw new IOException("Empty texture " + texture);
                            }
                        }
                        sources.add(new Source(role, texture, material.doubleSided(), batch.mesh()));
                        for (int i = 0; i < batch.mesh().vertices().length; i += 8) {
                            var v = batch.mesh().vertices();
                            radius = Math.max(radius, Math.sqrt(v[i] * (double)v[i] + v[i + 1] * (double)v[i + 1] + v[i + 2] * (double)v[i + 2]));
                        }
                    }
                }
                if (!missing.isEmpty() || sources.isEmpty()) throw new IOException(role + ": missing/empty OBJ objects " + missing);
                var offset = vector(config, "offset");
                var rotation = vector(config, "rotation");
                var normal = role.equals("throw_rod") ? vector(config, "normalTranslation") : new Vector3f();
                var reverse = role.equals("throw_rod") ? vector(config, "reverseTranslation") : new Vector3f();
                padding = Math.max(padding, radius + offset.length() + Math.max(normal.length(), reverse.length()));
                var matrix = new Matrix4f().translation(offset).rotateXYZ((float)Math.toRadians(rotation.x),
                        (float)Math.toRadians(rotation.y), (float)Math.toRadians(rotation.z));
                parts.put(role, new Part(List.copyOf(sources), matrix, normal, reverse));
            }
            var digest = MessageDigest.getInstance("SHA-256");
            bytes.forEach((id, content) -> {
                digest.update(id.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0); digest.update(content);
            });
            return new Model(Map.copyOf(parts), HexFormat.of().formatHex(digest.digest()), null, padding);
        } catch (IOException | RuntimeException | NoSuchAlgorithmException exception) {
            LOGGER.warn("Native style {} resource preparation failed; using procedural geometry: {}", CONFIG, exception.getMessage());
            return new Model(Map.of(), "missing", exception.toString(), 0);
        }
    }

    private static byte[] read(ResourceManager resources, ResourceLocation id, Map<ResourceLocation, byte[]> bytes) throws IOException {
        var content = bytes.get(id);
        if (content == null) {
            try (var input = resources.getResourceOrThrow(id).open()) { content = input.readAllBytes(); }
            bytes.put(id, content);
        }
        return content;
    }

    private static Vector3f vector(JsonObject json, String field) throws IOException {
        var array = GsonHelper.getAsJsonArray(json, field);
        if (array.size() != 3) throw new IOException(field + " needs three finite numbers");
        var result = new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
        if (!result.isFinite()) throw new IOException(field + " needs three finite numbers");
        return result;
    }

    @Override protected void apply(Model prepared, ResourceManager resources, ProfilerFiller profiler) {
        ClientTrackCache.INSTANCE.invalidateMeshes();
        layouts.clear();
        model = prepared;
    }
}
