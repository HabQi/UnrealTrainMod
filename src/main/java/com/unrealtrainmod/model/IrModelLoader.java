/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import com.unrealtrainmod.UnrealTrainMod;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Package-local OBJ/MTL lookup and orientation for a static IR vehicle. No files are extracted. */
public final class IrModelLoader {
    private static final Charset LEGACY_MTL_CHARSET = Charset.forName("GB18030");
    private static final Pattern HIDDEN = Pattern.compile("(?:^|_)HIDE(?:_|$)");

    public record Imported(ModelData model, Map<String, TrainModels.Texture> textures) {
        public Imported { textures = Map.copyOf(textures); }
    }

    private IrModelLoader() {}

    public static Imported load(RtmAsset asset) throws IOException {
        try (var zip = new ZipFile(asset.archive().toFile());
             var reader = new InputStreamReader(zip.getInputStream(requireEntry(zip, asset.entry())), StandardCharsets.UTF_8.newDecoder())) {
            var raw = ObjParser.parse(reader, asset.entry(), path -> openMaterial(zip, path));
            var textures = new HashMap<String, TrainModels.Texture>();
            var textureEntries = new HashMap<String, ZipEntry>();
            for (var material : raw.materials()) {
                String path = material.texture();
                if (path == null) continue;
                // Cache failed lookups too, so shared references produce one warning per model load.
                if (!textureEntries.containsKey(path)) textureEntries.put(path, findTexture(zip, asset, path));
                var entry = textureEntries.get(path);
                if (entry != null) {
                    textures.put(material.name(), new TrainModels.Texture(new RtmAsset(asset.archive(), entry.getName()), false));
                }
            }
            var parts = new ArrayList<ModelData.Part>();
            for (var part : raw.parts()) {
                var positions = part.positions().stream().map(p -> new ModelData.Position(-p.z(), p.y(), p.x())).toList();
                for (var face : part.faces()) {
                    // These arrays are owned by this fresh parse. Rotate normals with positions, without reflection.
                    float[] normals = face.normals();
                    for (int n = 0; n < normals.length; n += 3) {
                        float x = normals[n];
                        normals[n] = -normals[n + 2];
                        normals[n + 2] = x;
                    }
                }
                parts.add(new ModelData.Part(part.name(), isVisible(part.name()), false, 0, positions, part.faces()));
            }
            return new Imported(new ModelData(raw.materials(), parts), textures);
        }
    }

    /** Cargo volumes and explicitly hidden controls are retained for named-part callers, but not drawn by default. */
    static boolean isVisible(String name) {
        return !name.contains("CARGO_ITEMS_") && !name.contains("CARGO_FILL_") && !HIDDEN.matcher(name).find();
    }

    private static ZipEntry findTexture(ZipFile zip, RtmAsset model, String path) {
        var exact = zip.getEntry(path);
        if (exact != null && !exact.isDirectory()) return exact;
        var matches = zip.stream().filter(entry -> !entry.isDirectory() && entry.getName().equalsIgnoreCase(path))
                .limit(2).toList();
        if (matches.size() == 1) {
            var entry = matches.getFirst();
            UnrealTrainMod.LOGGER.warn("IR {}: texture '{}' matched ZIP entry '{}' ignoring case", model, path, entry.getName());
            return entry;
        }
        if (matches.isEmpty()) {
            UnrealTrainMod.LOGGER.warn("IR {}: missing texture '{}'; using material color", model, path);
        } else {
            UnrealTrainMod.LOGGER.warn("IR {}: ambiguous case-insensitive texture '{}'; using material color", model, path);
        }
        return null;
    }

    private static Reader openMaterial(ZipFile zip, String path) throws IOException {
        byte[] bytes;
        try (var input = zip.getInputStream(requireEntry(zip, path))) {
            bytes = input.readAllBytes();
        }
        try {
            return new StringReader(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException utf8) {
            // Reference YZ25DT MTL has legacy bytes in an otherwise ASCII Blender export.
            try {
                return new StringReader(LEGACY_MTL_CHARSET.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            } catch (CharacterCodingException legacy) {
                throw new IOException("Unsupported MTL text encoding: " + path, legacy);
            }
        }
    }

    private static ZipEntry requireEntry(ZipFile zip, String path) throws IOException {
        var entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing ZIP resource: " + path);
        return entry;
    }
}
