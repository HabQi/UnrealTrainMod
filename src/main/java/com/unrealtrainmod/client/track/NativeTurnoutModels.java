/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.texture.PbrSource;
import com.unrealtrainmod.client.texture.PreparedTexture;
import com.unrealtrainmod.client.texture.TextureSizing;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.ObjParser;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import com.unrealtrainmod.track.model.RailDefinition;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import org.slf4j.LoggerFactory;

/** Reload preparation only; prepared parts and their complete example use the existing rail manager. */
public final class NativeTurnoutModels {
    public static final ResourceLocation TEXTURE = id("textures/native_turnout/r11/r11.png");
    private static final String ROOT = "models/native_turnout/r11/";
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
        var sources = sources(parts);
        var example = NativeTurnoutMeshes.build(NativeTurnoutLayout.example(), sources, null, true, 0);
        var b = example.bounds();
        var bounds = new ModelBounds((float)b.minX(), (float)b.minY(), (float)b.minZ(),
                (float)b.maxX(), (float)b.maxY(), (float)b.maxZ());
        var batches = example.batches().stream().map(batch ->
                new ClientRailModelManager.Batch(batch.mesh(), batch.texture(), batch.doubleSided())).toList();
        return new ClientRailModelManager.Model(batches, parts, padding, bounds, RailDefinition.Format.IR_OBJ, 0.5, false, null);
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
            hash.update("native-r11-layout-3".getBytes(StandardCharsets.UTF_8));
            var paths = new ArrayList<String>(List.of(ROOT + "parts.json", ROOT + "r11.mtl", TEXTURE.getPath()));
            for (String file : files(resources)) paths.add(ROOT + "parts/" + file);
            for (String path : paths) try (var input = resources.getResourceOrThrow(id(path)).open()) {
                hash.update(path.getBytes(StandardCharsets.UTF_8)); hash.update(input.readAllBytes());
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
