/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.client.compat.iris.IrisGeometryCache;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.texture.PbrSource;
import com.unrealtrainmod.client.texture.PreparedTexture;
import com.unrealtrainmod.client.texture.TextureSizing;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.ObjParser;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import com.unrealtrainmod.track.model.RailDefinition;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import org.slf4j.LoggerFactory;

/** Native resources and layout-owned derived geometry share the existing rail manager. */
public final class NativeTurnoutModels {
    public static final ResourceLocation TEXTURE = id("textures/native_turnout/r11/r11.png");
    private static final String ROOT = "models/native_turnout/r11/";
    private record DerivedKey(IndexedMesh mesh, float length, NativeTurnoutLayout.BladeContact contact, NativeTurnoutLayout.RailBend bend) {}
    private static final Map<DerivedKey, DerivedSource> DERIVED = new LinkedHashMap<>();
    private static final Map<Source, DerivedSource> OWNED = new IdentityHashMap<>();
    private static final class DerivedSource {
        final DerivedKey key;
        final Source source;
        int references;
        DerivedSource(DerivedKey key, Source source) { this.key = key; this.source = source; }
    }
    private NativeTurnoutModels() {}
    static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("unrealtrainmod", path); }

    private static List<String> files(ResourceManager resources) throws IOException {
        try (var input = resources.getResourceOrThrow(id(ROOT + "parts.json")).openAsReader()) {
            var list = GsonHelper.parse(input).getAsJsonArray("parts");
            var result = new ArrayList<String>();
            for (var part : list) result.add(GsonHelper.getAsString(part.getAsJsonObject(), "file"));
            return List.copyOf(result);
        }
    }

    static ClientRailModelManager.Model load(ResourceManager resources) throws IOException {
        var parts = new ArrayList<ClientRailModelManager.Part>();
        double padding = 0;
        for (String file : files(resources)) {
            var location = id(ROOT + "parts/" + file);
            try (var reader = resources.getResourceOrThrow(location).openAsReader()) {
                var data = ObjParser.parse(reader, location.getPath(), path ->
                        new InputStreamReader(resources.getResourceOrThrow(id(path)).open(), StandardCharsets.UTF_8));
                var prepared = ModelMesh.prepare(data, 0.01f, false, true);
                if (prepared.fanFallbackCount() > 0) LoggerFactory.getLogger(NativeTurnoutModels.class)
                        .warn("Native r11 {} used source-corner triangulation for {} faces", file, prepared.fanFallbackCount());
                for (var part : prepared.parts()) {
                    var batches = new ArrayList<ClientRailModelManager.Batch>();
                    for (var batch : part.batches()) {
                        var source = batch.mesh();
                        float[] vertices = source.vertices().clone();
                        for (int i = 0; i < vertices.length; i += 8) {
                            vertices[i + 1] -= 0.25f;
                            padding = Math.max(padding, Math.hypot(vertices[i], vertices[i + 1]) + Math.abs(vertices[i + 2]));
                        }
                        batches.add(new ClientRailModelManager.Batch(new IndexedMesh(vertices, source.colors(), source.indices()),
                                TEXTURE, batch.material().doubleSided()));
                    }
                    parts.add(new ClientRailModelManager.Part(part.name(), batches));
                }
            }
        }
        var layout = NativeTurnoutLayout.example();
        var sources = NativeTurnoutMeshes.prepare(layout, sources(parts));
        RailTrackMeshData example;
        try { example = NativeTurnoutMeshes.build(layout, sources, null, true, 0); }
        finally { NativeTurnoutMeshes.release(sources); }
        var b = example.bounds();
        var bounds = new ModelBounds((float)b.minX(), (float)b.minY(), (float)b.minZ(),
                (float)b.maxX(), (float)b.maxY(), (float)b.maxZ());
        var batches = example.batches().stream().map(batch ->
                new ClientRailModelManager.Batch(batch.mesh(), batch.texture(), batch.doubleSided())).toList();
        return new ClientRailModelManager.Model(batches, parts, Math.max(padding, 6.5), bounds, RailDefinition.Format.IR_OBJ, 0.5, false, null);
    }

    static Source blade(Source source, Source rail, float length, NativeTurnoutLayout.BladeContact contact) {
        return derived(new DerivedKey(source.mesh(), length, contact, null), () ->
                new Source(source.part(), source.texture(), source.doubleSided(),
                        NativeTurnoutCurves.blade(source.mesh(), rail.mesh(), length, contact)));
    }
    static Source rail(Source source, float length, NativeTurnoutLayout.RailBend bend) {
        return derived(new DerivedKey(source.mesh(), length, null, bend), () ->
                new Source(source.part(), source.texture(), source.doubleSided(),
                        NativeTurnoutCurves.rail(source.mesh(), length, bend, source.part().equals("railL") ? 0.74875 : -0.74875)));
    }
    private static synchronized Source derived(DerivedKey key, Supplier<Source> build) {
        var entry = DERIVED.get(key);
        if (entry == null) { entry = new DerivedSource(key, build.get()); DERIVED.put(key, entry); OWNED.put(entry.source, entry); }
        entry.references++;
        return entry.source;
    }
    static void release(Source source) {
        synchronized (NativeTurnoutModels.class) {
            var entry = OWNED.get(source);
            if (entry == null || --entry.references > 0) return;
            DERIVED.remove(entry.key); OWNED.remove(source);
        }
        Runnable release = () -> {
            ClientRailModelManager.INSTANCE.releaseSharedPart(source.mesh());
            IrisGeometryCache.releaseMesh(source.mesh());
        };
        if (RenderSystem.isOnRenderThread()) release.run(); else RenderSystem.recordRenderCall(release::run);
    }
    static synchronized void clearDerivedSources() {
        DERIVED.clear(); OWNED.clear();
    }

    static Map<String, List<Source>> sources(List<ClientRailModelManager.Part> parts) {
        var result = new LinkedHashMap<String, List<Source>>();
        for (var part : parts) result.put(part.name(), part.batches().stream().map(batch ->
                new Source(part.name(), batch.texture(), batch.doubleSided(), batch.mesh())).toList());
        return Map.copyOf(result);
    }

    static PreparedTexture texture(ResourceManager resources) throws IOException {
        NativeImage image;
        try (var input = resources.getResourceOrThrow(TEXTURE).open()) {
            image = TextureSizing.limit(NativeImage.read(input), ClientConfig.MAX_PACKAGE_TEXTURE_SIZE.get());
        }
        try {
            return new PreparedTexture(image, new PbrSource.Resources(
                    resources.getResource(id("textures/native_turnout/r11/r11_n.png")).orElse(null),
                    resources.getResource(id("textures/native_turnout/r11/r11_s.png")).orElse(null),
                    image.getWidth(), image.getHeight()));
        } catch (RuntimeException | Error exception) { image.close(); throw exception; }
    }

    public static String fingerprint(ResourceManager resources) throws IOException {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update("native-r11-layout-6".getBytes(StandardCharsets.UTF_8));
            var paths = new ArrayList<String>(List.of(ROOT + "parts.json", ROOT + "r11.mtl", TEXTURE.getPath()));
            for (String file : files(resources)) paths.add(ROOT + "parts/" + file);
            for (String path : paths) try (var input = resources.getResourceOrThrow(id(path)).open()) {
                hash.update(path.getBytes(StandardCharsets.UTF_8)); hash.update(input.readAllBytes());
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
