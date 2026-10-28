/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;

/** Independent reader for diffuse colors, opacity and untransformed diffuse texture references. */
public final class MtlParser {
    private MtlParser() {}

    /** Paths are relative to the logical resource root. The caller owns the reader and its encoding. */
    public static List<ModelData.Material> parse(Reader reader, String sourcePath) throws IOException {
        var text = new WavefrontReader(reader, sourcePath);
        var materials = new LinkedHashMap<String, ModelData.Material>();
        MaterialBuilder current = null;
        String[] line;
        while ((line = text.next()) != null) {
            if (line[0].equals("newmtl")) {
                if (current != null) materials.put(current.name, current.build());
                current = new MaterialBuilder(text.argument());
                continue;
            }
            switch (line[0]) {
                case "Kd", "d", "Tr", "map_Kd" -> {
                    if (current == null) throw text.error("Material property before newmtl");
                    switch (line[0]) {
                        case "Kd" -> {
                            text.count(line, 4, 4);
                            current.red = text.number(line[1]);
                            current.green = text.number(line[2]);
                            current.blue = text.number(line[3]);
                        }
                        case "d", "Tr" -> {
                            text.count(line, 2, 2);
                            float value = text.number(line[1]);
                            if (value < 0 || value > 1) throw text.error("Opacity must be between 0 and 1");
                            current.alpha = line[0].equals("Tr") ? 1 - value : value;
                        }
                        case "map_Kd" -> {
                            String reference = text.argument();
                            if (reference.startsWith("-")) throw text.error("map_Kd options are not supported");
                            current.texture = text.resolve(reference);
                        }
                    }
                }
                // Lighting, PBR and other maps are outside the static diffuse material subset.
                default -> {}
            }
        }
        if (current != null) materials.put(current.name, current.build());
        return List.copyOf(materials.values());
    }

    private static final class MaterialBuilder {
        private final String name;
        private float red = 1, green = 1, blue = 1, alpha = 1;
        private String texture;

        private MaterialBuilder(String name) { this.name = name; }

        private ModelData.Material build() {
            return new ModelData.Material(name, red, green, blue, alpha, texture, false);
        }
    }
}
