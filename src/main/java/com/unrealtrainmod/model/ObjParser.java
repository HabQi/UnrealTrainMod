/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Independent polygon OBJ importer. Coordinates and authored origin are preserved. */
public final class ObjParser {
    @FunctionalInterface
    public interface MaterialLibraryResolver {
        /** Opens a normalized, case-sensitive path relative to the logical resource root. */
        Reader open(String path) throws IOException;
    }

    private final WavefrontReader text;
    private final MaterialLibraryResolver libraries;
    private final List<ModelData.Position> positions = new ArrayList<>();
    private final List<float[]> uvs = new ArrayList<>();
    private final List<float[]> normals = new ArrayList<>();
    private final Map<String, ModelData.Material> materials = new LinkedHashMap<>();
    private final List<PartBuilder> parts = new ArrayList<>();
    private PartBuilder part;
    private String material;
    private int smoothingGroup;
    private boolean missingNormals;

    private ObjParser(Reader reader, String sourcePath, MaterialLibraryResolver libraries) throws IOException {
        text = new WavefrontReader(reader, sourcePath);
        this.libraries = libraries;
    }

    /**
     * Leaves the OBJ reader open; closes each reader obtained from the material resolver.
     * The caller chooses each reader's encoding. Source and returned texture paths are relative
     * to the same logical resource root, e.g. assets/immersiverailroading/models/train.obj.
     * Output faces use ModelData's clockwise convention and top-origin texture coordinates.
     */
    public static ModelData parse(Reader reader, String sourcePath, MaterialLibraryResolver libraries) throws IOException {
        return new ObjParser(reader, sourcePath, libraries).readModel();
    }

    /** Scans polygon-referenced positions without retaining faces or opening material libraries. */
    public static ModelBounds scanBounds(Reader reader, String sourcePath) throws IOException {
        return scanBounds(reader, sourcePath, ignored -> true);
    }

    /** The part filter uses the same consecutive o/g names as full parsing. */
    public static ModelBounds scanBounds(Reader reader, String sourcePath, Predicate<String> includePart) throws IOException {
        var text = new WavefrontReader(reader, sourcePath);
        var positions = new ArrayList<ModelData.Position>();
        var bounds = new ModelBounds.Builder();
        boolean visible = includePart.test("default");
        String[] line;
        while ((line = text.next()) != null) {
            switch (line[0]) {
                case "v" -> positions.add(readPosition(text, line));
                case "o", "g" -> visible = includePart.test(line.length == 1 ? "default" : text.argument());
                case "f" -> {
                    text.count(line, 4, 4097);
                    for (int i = 1; i < line.length; i++) {
                        String[] corner = readCorner(text, line[i]);
                        var p = positions.get(index(text, corner[0], positions.size(), "position"));
                        if (visible) bounds.include(p.x(), p.y(), p.z());
                    }
                }
                default -> checkGeometry(text, line[0]);
            }
        }
        var result = bounds.build();
        if (result == null) throw text.error("OBJ contains no included polygon faces");
        return result;
    }

    private static ModelData.Position readPosition(WavefrontReader text, String[] line) throws IOException {
        text.count(line, 4, 5);
        float x = text.number(line[1]), y = text.number(line[2]), z = text.number(line[3]);
        if (line.length == 5 && text.number(line[4]) != 1) {
            throw text.error("Only vertex weight 1 is supported");
        }
        return new ModelData.Position(x, y, z);
    }

    private static void checkGeometry(WavefrontReader text, String directive) throws IOException {
        switch (directive) {
            case "vp", "cstype", "deg", "bmat", "step", "curv", "curv2", "surf",
                    "parm", "trim", "hole", "scrv", "sp", "end", "con" ->
                    throw text.error("Unsupported non-polygon geometry: " + directive);
            default -> {} // Grouping/display metadata does not change polygon geometry.
        }
    }

    private ModelData readModel() throws IOException {
        String[] line;
        while ((line = text.next()) != null) {
            switch (line[0]) {
                case "v" -> positions.add(readPosition(text, line));
                case "vt" -> {
                    text.count(line, 2, 4);
                    float u = text.number(line[1]), v = line.length >= 3 ? text.number(line[2]) : 0;
                    if (line.length == 4 && text.number(line[3]) != 0) {
                        throw text.error("Only 2D texture coordinates are supported");
                    }
                    uvs.add(new float[]{u, 1 - v});
                }
                case "vn" -> {
                    text.count(line, 4, 4);
                    float x = text.number(line[1]), y = text.number(line[2]), z = text.number(line[3]);
                    double length = Math.sqrt((double)x * x + (double)y * y + (double)z * z);
                    // Some reference-pack exports contain zero vn entries; generate those corners below.
                    normals.add(length == 0 ? new float[3]
                            : new float[]{(float)(x / length), (float)(y / length), (float)(z / length)});
                }
                case "f" -> readFace(line);
                case "o", "g" -> startPart(line.length == 1 ? "default" : text.argument());
                case "s" -> {
                    text.count(line, 2, 2);
                    smoothingGroup = switch (line[1]) {
                        case "off", "0" -> 0;
                        case "on" -> 1;
                        default -> text.integer(line[1]);
                    };
                    if (smoothingGroup < 0) throw text.error("Smoothing group must not be negative");
                }
                case "usemtl" -> material = text.argument();
                case "mtllib" -> readLibraries(line);
                case "l", "p" -> {} // The triangle renderer does not consume lines or points.
                default -> checkGeometry(text, line[0]);
            }
        }
        if (parts.stream().allMatch(p -> p.faces.isEmpty())) throw text.error("OBJ contains no polygon faces");
        if (missingNormals) generateNormals();
        var materialList = List.copyOf(materials.values());
        var materialIndices = new HashMap<String, Integer>();
        for (int i = 0; i < materialList.size(); i++) materialIndices.put(materialList.get(i).name(), i);
        var result = new ArrayList<ModelData.Part>();
        for (var source : parts) {
            var faces = new ArrayList<ModelData.Face>(source.faces.size());
            for (var parsed : source.faces) {
                int index = -1;
                if (parsed.material() != null) {
                    Integer resolved = materialIndices.get(parsed.material());
                    if (resolved == null) throw text.error(parsed.lineNumber(), "Undefined material: " + parsed.material());
                    index = resolved;
                }
                var face = parsed.face();
                faces.add(new ModelData.Face(face.indices(), face.uv(), face.colors(), index, face.normals()));
            }
            // All OBJ normals are already authored or generated; MQO smoothing must not replace them.
            result.add(new ModelData.Part(source.name, true, false, 0, source.positions, faces));
        }
        return new ModelData(materialList, result);
    }

    private void startPart(String name) {
        part = new PartBuilder(name);
        parts.add(part);
    }

    private void readLibraries(String[] line) throws IOException {
        if (line.length == 1) throw text.error("Missing material library path");
        for (int i = 1; i < line.length; i++) {
            String path = text.resolve(line[i]);
            try (var reader = libraries.open(path)) {
                for (var entry : MtlParser.parse(reader, path)) materials.put(entry.name(), entry);
            } catch (IOException e) {
                throw new IOException(text.error("Unable to load material library " + path).getMessage()
                        + ": " + e.getMessage(), e);
            }
        }
    }

    private void readFace(String[] line) throws IOException {
        text.count(line, 4, 4097);
        if (part == null) startPart("default");
        int count = line.length - 1;
        int[] indices = new int[count], colors = new int[count];
        float[] uv = new float[count * 2], cornerNormals = new float[count * 3];
        Arrays.fill(colors, -1);
        boolean missing = false;
        for (int i = 0; i < count; i++) {
            String[] corner = readCorner(text, line[i + 1]);
            // Reverse every corner attribute together; explicit normals still point outwards.
            int target = count - i - 1;
            int sourceIndex = index(text, corner[0], positions.size(), "position");
            Integer localIndex = part.positionIndices.get(sourceIndex);
            if (localIndex == null) {
                localIndex = part.positions.size();
                part.positionIndices.put(sourceIndex, localIndex);
                part.sourceIndices.add(sourceIndex);
                part.positions.add(positions.get(sourceIndex));
            }
            indices[target] = localIndex;
            if (corner.length >= 2 && !corner[1].isEmpty()) {
                float[] tex = uvs.get(index(text, corner[1], uvs.size(), "texture coordinate"));
                uv[target * 2] = tex[0]; uv[target * 2 + 1] = tex[1];
            }
            if (corner.length == 3) {
                float[] normal = normals.get(index(text, corner[2], normals.size(), "normal"));
                System.arraycopy(normal, 0, cornerNormals, target * 3, 3);
                missing |= missing(normal, 0);
            } else {
                missing = true;
            }
        }
        var face = new ModelData.Face(indices, uv, colors, -1, cornerNormals);
        part.faces.add(new ParsedFace(face, material, smoothingGroup, text.lineNumber(), missing));
        missingNormals |= missing;
    }

    private static String[] readCorner(WavefrontReader text, String value) throws IOException {
        String[] corner = value.split("/", -1);
        if (corner.length > 3 || corner[0].isEmpty()
                || (corner.length == 2 && corner[1].isEmpty())
                || (corner.length == 3 && corner[2].isEmpty())) {
            throw text.error("Expected v, v/vt, v//vn or v/vt/vn: " + value);
        }
        return corner;
    }

    private static int index(WavefrontReader text, String value, int size, String kind) throws IOException {
        int declared = text.integer(value);
        int resolved = declared < 0 ? size + declared : declared - 1;
        if (declared == 0 || resolved < 0 || resolved >= size) {
            throw text.error("Invalid " + kind + " index: " + value);
        }
        return resolved;
    }

    private void generateNormals() {
        // Global OBJ position indices keep smoothing continuous across o/g part boundaries.
        // Only vertices with a missing normal need a sum; fully authored models skip this pass.
        var sums = new HashMap<Long, double[]>();
        for (var source : parts) for (var parsed : source.faces) {
            if (!parsed.missingNormals() || parsed.smoothingGroup() == 0) continue;
            var face = parsed.face();
            for (int c = 0; c < face.indices().length; c++) if (missing(face.normals(), c)) {
                sums.computeIfAbsent(smoothKey(source, parsed, c), ignored -> new double[3]);
            }
        }
        for (var source : parts) for (var parsed : source.faces) {
            var face = parsed.face();
            if (parsed.smoothingGroup() == 0) {
                if (parsed.missingNormals()) {
                    float[] normal = ModelMesh.normal(face, source.positions);
                    for (int c = 0; c < face.indices().length; c++) if (missing(face.normals(), c)) {
                        System.arraycopy(normal, 0, face.normals(), c * 3, 3);
                    }
                }
                continue;
            }
            float[] normal = null;
            for (int c = 0; c < face.indices().length; c++) {
                double[] sum = sums.get(smoothKey(source, parsed, c));
                if (sum == null) continue;
                if (normal == null) normal = ModelMesh.normal(face, source.positions);
                sum[0] += normal[0]; sum[1] += normal[1]; sum[2] += normal[2];
            }
        }
        for (var source : parts) for (var parsed : source.faces) {
            if (!parsed.missingNormals() || parsed.smoothingGroup() == 0) continue;
            var face = parsed.face();
            float[] fallback = null;
            for (int c = 0; c < face.indices().length; c++) if (missing(face.normals(), c)) {
                double[] sum = sums.get(smoothKey(source, parsed, c));
                double length = Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
                if (length > 0) {
                    face.normals()[c * 3] = (float)(sum[0] / length);
                    face.normals()[c * 3 + 1] = (float)(sum[1] / length);
                    face.normals()[c * 3 + 2] = (float)(sum[2] / length);
                } else {
                    if (fallback == null) fallback = ModelMesh.normal(face, source.positions);
                    System.arraycopy(fallback, 0, face.normals(), c * 3, 3);
                }
            }
        }
    }

    private static boolean missing(float[] normals, int corner) {
        int offset = corner * 3;
        return normals[offset] == 0 && normals[offset + 1] == 0 && normals[offset + 2] == 0;
    }

    private static long smoothKey(PartBuilder part, ParsedFace face, int corner) {
        return (long)face.smoothingGroup() << 32 | part.sourceIndices.get(face.face().indices()[corner]);
    }

    private record ParsedFace(ModelData.Face face, String material, int smoothingGroup,
                              int lineNumber, boolean missingNormals) {}

    private static final class PartBuilder {
        private final String name;
        private final List<ModelData.Position> positions = new ArrayList<>();
        private final Map<Integer, Integer> positionIndices = new HashMap<>();
        private final List<Integer> sourceIndices = new ArrayList<>();
        private final List<ParsedFace> faces = new ArrayList<>();

        private PartBuilder(String name) { this.name = name; }
    }
}
