/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.track.mesh.NativeLayoutProfile;
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
    private static final String TEXTURES = "textures/native_turnout/r11_simplified/";
    public static final ResourceLocation TEXTURE = id(TEXTURES + "r11.png");
    private static final String ROOT = "models/native_turnout/r11_simplified/";
    private static final List<String> PARAMETER_PARTS = List.of("L1", "R1", "railL", "railR");
    private record PartResource(String file, String parameters, int duplicateTrianglesRemoved) {}
    private record DerivedKey(IndexedMesh mesh, double length, NativeTurnoutLayout.BladeContact contact,
                              NativeTurnoutLayout.RailBend bend, boolean fixed, NativeTurnoutCurves.SlipShape slip) {}
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

    private static List<PartResource> files(ResourceManager resources) throws IOException {
        try (var input = resources.getResourceOrThrow(id(ROOT + "parts.json")).openAsReader()) {
            var list = GsonHelper.parse(input).getAsJsonArray("parts");
            var result = new ArrayList<PartResource>();
            for (var part : list) {
                var entry = part.getAsJsonObject();
                result.add(new PartResource(GsonHelper.getAsString(entry, "file"), GsonHelper.getAsString(entry, "parameters", null),
                        GsonHelper.getAsInt(entry,"sourceDuplicateTrianglesRemoved",0)));
            }
            return List.copyOf(result);
        }
    }

    static ClientRailModelManager.Model load(ResourceManager resources) throws IOException {
        var parts = new ArrayList<ClientRailModelManager.Part>();
        double padding = 0;
        for (var entry : files(resources)) {
            String file = entry.file();
            var location = id(ROOT + "parts/" + file);
            try (var stage = NativeLayoutProfile.stage("resource/obj/"+file);
                 var reader = resources.getResourceOrThrow(location).openAsReader()) {
                if (stage != null) stage.value("sourceDuplicateTrianglesRemoved",entry.duplicateTrianglesRemoved());
                ModelData data;
                try (var parsing = NativeLayoutProfile.stage("resource/objParse")) {
                    data = ObjParser.parse(reader, location.getPath(), path ->
                            new InputStreamReader(resources.getResourceOrThrow(id(path)).open(), StandardCharsets.UTF_8));
                }
                ModelMesh prepared;
                List<ModelMesh.Part> renderParts;
                try (var preparing = NativeLayoutProfile.stage("resource/meshPrepare")) {
                    prepared = ModelMesh.prepare(data, 0.01f, false, true);
                    renderParts = prepared.parts().stream().filter(part -> !part.batches().isEmpty()).toList();
                    if (preparing != null) preparing.value("parts", renderParts.size());
                }
                if (prepared.fanFallbackCount() > 0) LoggerFactory.getLogger(NativeTurnoutModels.class)
                        .warn("Native r11 {} used source-corner triangulation for {} faces", file, prepared.fanFallbackCount());
                float[] parameters = null;
                if (entry.parameters() != null) {
                    if (renderParts.size() != 1 || renderParts.getFirst().batches().size() != 1)
                        throw new IOException("Parameterized native OBJ must contain one part and one material: " + file);
                    try (var parameterReader = resources.getResourceOrThrow(id(ROOT + "parts/" + entry.parameters())).openAsReader()) {
                        var values = GsonHelper.parse(parameterReader).getAsJsonArray("parameterV");
                        parameters = new float[values.size()];
                        for (int i = 0; i < parameters.length; i++) parameters[i] = values.get(i).getAsFloat();
                    }
                }
                for (var part : renderParts) {
                    if (parameters == null && PARAMETER_PARTS.contains(part.name()))
                        throw new IOException("Missing native deformation parameters for " + file);
                    var batches = new ArrayList<ClientRailModelManager.Batch>();
                    for (var batch : part.batches()) {
                        var source = batch.mesh();
                        float[] vertices = source.vertices().clone();
                        for (int i = 0; i < vertices.length; i += 8) {
                            vertices[i + 1] -= 0.25f;
                            padding = Math.max(padding, Math.hypot(vertices[i], vertices[i + 1]) + Math.abs(vertices[i + 2]));
                        }
                        var mesh = new IndexedMesh(vertices, source.colors(), source.indices());
                        if (part.name().equals("railL") || part.name().equals("railR")) validateFixedProfile(mesh,part.name());
                        NativeSourceIdentity.register(mesh);
                        if (parameters != null) NativeTurnoutCurves.parameters(mesh, parameters);
                        batches.add(new ClientRailModelManager.Batch(mesh, TEXTURE, batch.material().doubleSided()));
                    }
                    parts.add(new ClientRailModelManager.Part(part.name(), batches));
                }
            }
        }
        NativeTurnoutLayout layout;
        try (var stage = NativeLayoutProfile.stage("icon/turnoutExample")) {
            layout = NativeTurnoutLayout.example();
        }
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

    private static void validateFixedProfile(IndexedMesh mesh, String name) throws IOException {
        double center = name.equals("railL") ? .74875 : -.74875;
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY; var data = mesh.vertices();
        for (int i = 0; i < data.length; i += 8) {
            min = Math.min(min,data[i+2]); max = Math.max(max,data[i+2]);
            if (Math.abs(data[i]-center) > .075001 || data[i+1] < -.176001 || data[i+1] > .000001)
                throw new IOException("Native fixed rail profile exceeds the supported 150 mm x 176 mm envelope: "+name);
        }
        if (max-min <= 1e-6f) throw new IOException("Native fixed rail source has no longitudinal extent: "+name);
    }

    static Source blade(Source source, Source rail, float length, NativeTurnoutLayout.BladeContact contact) {
        return derived(new DerivedKey(source.mesh(), length, contact, null, false, null), () ->
                new Source(source.part(), source.texture(), source.doubleSided(),
                        NativeTurnoutCurves.blade(source.mesh(), rail.mesh(), length, contact)));
    }
    static Source rail(Source source, float length, NativeTurnoutLayout.RailBend bend) {
        return derived(new DerivedKey(source.mesh(), length, null, bend, false, null), () ->
                new Source(source.part(), source.texture(), source.doubleSided(),
                        NativeTurnoutCurves.rail(source.mesh(), length, bend, source.part().equals("railL") ? 0.74875 : -0.74875)));
    }
    static Source fixedRail(Source source, double length, NativeTurnoutLayout.RailBend bend) {
        return derived(new DerivedKey(source.mesh(),length,null,bend,true,null), () ->
                new Source(source.part(),source.texture(),source.doubleSided(),NativeTurnoutCurves.fixedRail(source.mesh(),-length/2,length/2,
                        source.part().equals("railL") ? .74875 : -.74875,bend.curve(),0,0)));
    }
    static Source generated(String name, IndexedMesh mesh) {
        return derived(new DerivedKey(mesh, 0, null, null, false, null), () -> new Source(name, TEXTURE, false, mesh));
    }
    static Source slipStrip(Source source, NativeTurnoutCurves.SlipShape shape) {
        return derived(new DerivedKey(source.mesh(), 0, null, null, false, shape), () ->
                new Source(source.part(), source.texture(), source.doubleSided(), NativeTurnoutCurves.slipStrip(source.mesh(), shape)));
    }
    static Source retain(Source source) {
        synchronized (NativeTurnoutModels.class) {
            var owned = OWNED.get(source);
            if (owned != null) owned.references++;
        }
        return source;
    }
    private static Source derived(DerivedKey key, Supplier<Source> build) {
        synchronized (NativeTurnoutModels.class) {
            var cached = DERIVED.get(key);
            if (cached != null) { cached.references++; return cached.source; }
        }
        // Deformation must never hold the cache monitor needed by a frame-end release.
        Source source;
        try (var stage = NativeLayoutProfile.stage(key.contact() != null ? "geometry/bladeDeformation" : "geometry/railOrGeneratedSource")) {
            source = build.get();
        }
        synchronized (NativeTurnoutModels.class) {
            var entry = DERIVED.get(key);
            if (entry == null) {
                entry = new DerivedSource(key, source);
                DERIVED.put(key, entry); OWNED.put(source, entry);
            }
            entry.references++;
            return entry.source;
        }
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
        DERIVED.clear(); OWNED.clear(); NativeTurnoutCurves.clearProfiles();
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
                    resources.getResource(id(TEXTURES + "r11_n.png")).orElse(null),
                    resources.getResource(id(TEXTURES + "r11_s.png")).orElse(null),
                    image.getWidth(), image.getHeight()));
        } catch (RuntimeException | Error exception) { image.close(); throw exception; }
    }

    public static String fingerprint(ResourceManager resources) throws IOException {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update("native-r11-layout-20-fixed3m-stable-support-atlas1024".getBytes(StandardCharsets.UTF_8));
            var paths = new ArrayList<String>(List.of(ROOT + "parts.json", ROOT + "r11.mtl", TEXTURE.getPath()));
            for (var entry : files(resources)) {
                paths.add(ROOT + "parts/" + entry.file());
                if (entry.parameters() != null) paths.add(ROOT + "parts/" + entry.parameters());
            }
            for (String path : paths) try (var input = resources.getResourceOrThrow(id(path)).open()) {
                hash.update(path.getBytes(StandardCharsets.UTF_8)); hash.update(input.readAllBytes());
            }
            for (String channel : List.of("r11_n.png", "r11_s.png")) {
                String path = TEXTURES + channel;
                hash.update(path.getBytes(StandardCharsets.UTF_8));
                var resource = resources.getResource(id(path));
                hash.update((byte)(resource.isPresent() ? 1 : 0));
                if (resource.isPresent()) try (var input = resource.get().open()) { hash.update(input.readAllBytes()); }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
