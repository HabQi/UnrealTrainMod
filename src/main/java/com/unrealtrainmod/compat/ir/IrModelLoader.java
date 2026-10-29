/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelTexture;
import com.unrealtrainmod.model.ObjParser;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
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
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Package-local OBJ/MTL lookup and orientation. No files are extracted. */
public final class IrModelLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrModelLoader.class);
    private static final Charset LEGACY_MTL_CHARSET = Charset.forName("GB18030");
    private static final Pattern HIDDEN = Pattern.compile("(?:^|_)HIDE(?:_|$)");

    public record Imported(ModelData model, Map<String, ModelTexture> textures) {
        public Imported { textures = Map.copyOf(textures); }
    }

    private IrModelLoader() {}

    public static Imported load(PackageAsset asset) throws IOException {
        try (var archive = new PackageArchive(asset.archive())) {
            return load(archive, asset, IrModelLoader::isVisible);
        }
    }

    /** Uses the startup scan's archive; track objects do not inherit vehicle visibility rules. */
    public static Imported loadRail(PackageArchive archive, PackageAsset asset) throws IOException {
        return load(archive, asset, ignored -> true);
    }

    private static Imported load(PackageArchive archive, PackageAsset asset, Predicate<String> visible) throws IOException {
        try (var reader = new InputStreamReader(archive.open(asset.entry()), StandardCharsets.UTF_8.newDecoder())) {
            var raw = ObjParser.parse(reader, asset.entry(), path -> openMaterial(archive, path));
            var textures = new HashMap<String, ModelTexture>();
            var textureEntries = new HashMap<String, String>();
            for (var material : raw.materials()) {
                String path = material.texture();
                if (path == null) continue;
                // Cache failed lookups too, so shared references produce one warning per model load.
                if (!textureEntries.containsKey(path)) textureEntries.put(path, findTexture(archive, asset, path));
                var entry = textureEntries.get(path);
                if (entry != null) {
                    textures.put(material.name(), new ModelTexture(new PackageAsset(asset.archive(), entry), false));
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
                parts.add(new ModelData.Part(part.name(), visible.test(part.name()), false, 0, positions, part.faces()));
            }
            return new Imported(new ModelData(raw.materials(), parts), textures);
        }
    }

    /** Cargo volumes and explicitly hidden controls are retained for named-part callers, but not drawn by default. */
    public static boolean isVisible(String name) {
        return !name.contains("CARGO_ITEMS_") && !name.contains("CARGO_FILL_") && !HIDDEN.matcher(name).find();
    }

    private static String findTexture(PackageArchive archive, PackageAsset model, String path) {
        if (archive.contains(path)) return path;
        var matches = archive.entries().stream().filter(entry -> entry.equalsIgnoreCase(path))
                .limit(2).toList();
        if (matches.size() == 1) {
            var entry = matches.getFirst();
            LOGGER.warn("IR {}: texture '{}' matched ZIP entry '{}' ignoring case", model, path, entry);
            return entry;
        }
        if (matches.isEmpty()) {
            LOGGER.warn("IR {}: missing texture '{}'; using material color", model, path);
        } else {
            LOGGER.warn("IR {}: ambiguous case-insensitive texture '{}'; using material color", model, path);
        }
        return null;
    }

    private static Reader openMaterial(PackageArchive archive, String path) throws IOException {
        byte[] bytes;
        try (var input = archive.open(path)) {
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

}
