/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Minimal text MQO importer. Unsupported geometry modifiers fail explicitly. */
public final class MqoParser {
    private static final Pattern CODE_PAGE = Pattern.compile("(?im)^[ \\t]*CodePage[ \\t]+([\\w-]+)");
    private static final int MAX_COUNT = 2_000_000;
    private final BufferedReader reader;
    private int lineNumber;
    private ModelBounds.Builder scannedBounds;
    private float scannedScale;

    private MqoParser(Reader reader) {
        this.reader = new BufferedReader(reader);
    }

    public static ModelData parse(InputStream input, Charset fallback) throws IOException {
        return parse(openReader(input, fallback));
    }

    /** Scans visible vertices without retaining faces or constructing rendering geometry. */
    public static ModelBounds scanBounds(InputStream input, Charset fallback, float scale) throws IOException {
        if (!Float.isFinite(scale) || scale <= 0) throw new IOException("Model scale must be positive");
        var parser = new MqoParser(openReader(input, fallback));
        parser.scannedScale = scale;
        parser.scannedBounds = new ModelBounds.Builder();
        try {
            parser.readModel();
            var bounds = parser.scannedBounds.build();
            if (bounds == null) throw parser.error("MQO contains no visible vertices");
            return bounds;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw parser.error("Invalid number or incomplete record: " + e.getMessage());
        }
    }

    private static Reader openReader(InputStream input, Charset fallback) throws IOException {
        var buffered = new BufferedInputStream(input);
        buffered.mark(4096);
        var match = CODE_PAGE.matcher(new String(buffered.readNBytes(4096), StandardCharsets.ISO_8859_1));
        buffered.reset();
        Charset encoding = fallback;
        if (match.find()) {
            String code = match.group(1);
            try {
                encoding = Charset.forName(switch (code.toLowerCase(Locale.ROOT)) {
                    case "utf8", "utf-8", "65001" -> "UTF-8";
                    case "932" -> "windows-31j";
                    case "936" -> "GBK";
                    default -> "windows-" + code;
                });
            } catch (IllegalArgumentException e) {
                throw new IOException("Unsupported MQO CodePage: " + code, e);
            }
        }
        var decoder = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return new InputStreamReader(buffered, decoder);
    }

    public static ModelData parse(Reader reader) throws IOException {
        MqoParser parser = new MqoParser(reader);
        try {
            return parser.readModel();
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw parser.error("Invalid number or incomplete record: " + e.getMessage());
        }
    }

    private ModelData readModel() throws IOException {
        List<String> header = next();
        boolean compilation = header.equals(List.of("Compilation", "{"));
        if (compilation) header = required();
        if (!header.equals(List.of("Metasequoia", "Document"))) throw error("Missing MQO header");
        List<String> format = next();
        if (format.size() != 4 || !format.get(0).equals("Format") || !format.get(1).equals("Text")
                || !format.get(2).equals("Ver") || !List.of("1.0", "1.1", "1.2").contains(format.get(3))) {
            throw error("Supported MQO formats: Text Ver 1.0, 1.1, 1.2");
        }
        if (compilation) {
            List<String> line = required();
            if (key(line).equals("codepage")) line = required();
            if (!line.equals(List.of("}"))) throw error("Unclosed Compilation header");
        }
        List<ModelData.Material> materials = new ArrayList<>();
        List<ModelData.Part> parts = new ArrayList<>();
        List<String> line;
        while (!(line = next()).isEmpty()) {
            switch (key(line)) {
                case "material" -> {
                    if (scannedBounds != null) { skipBlock(line); break; }
                    int count = count(line.get(1));
                    requireBlock(line);
                    for (int i = 0; i < count; i++) materials.add(readMaterial(required()));
                    requireEnd();
                }
                case "object" -> parts.add(readPart(line));
                case "trialnoise" -> throw error("TrialNoise MQO documents are not supported");
                default -> skipBlock(line);
            }
        }
        for (var part : parts) {
            for (var face : part.faces()) {
                if (face.material() < -1 || face.material() >= materials.size()) {
                    throw error("Invalid material index in object " + part.name());
                }
            }
        }
        if (parts.isEmpty()) throw error("MQO contains no objects");
        return new ModelData(materials, parts);
    }

    private ModelData.Material readMaterial(List<String> line) throws IOException {
        float[] color = floats(attribute(line, "col"), 4, new float[]{1, 1, 1, 1});
        List<String> texture = attribute(line, "tex");
        List<String> doubleSided = attribute(line, "dbls");
        List<String> projection = attribute(line, "proj_type");
        if (!projection.isEmpty() && !projection.getFirst().equals("0")) {
            throw error("Bake projected material UVs before importing");
        }
        for (String a : List.of("alpha", "aplane", "bump")) {
            if (!attribute(line, a).isEmpty()) throw error("Unsupported material mapping: " + a);
        }
        return new ModelData.Material(value(line.getFirst()), color[0], color[1], color[2], color[3],
                texture.isEmpty() ? null : value(texture.getFirst()),
                !doubleSided.isEmpty() && !doubleSided.getFirst().equals("0"));
    }

    private ModelData.Part readPart(List<String> header) throws IOException {
        requireBlock(header);
        String name = value(header.get(1));
        boolean visible = true, smooth = false;
        float facet = 59.5f;
        List<ModelData.Position> vertices = new ArrayList<>();
        List<ModelData.Face> faces = new ArrayList<>();
        var partBounds = scannedBounds == null ? null : new ModelBounds.Builder();
        while (true) {
            List<String> line = required();
            switch (key(line)) {
                case "}" -> {
                    for (var face : faces) for (int index : face.indices()) {
                        if (index < 0 || index >= vertices.size()) throw error("Vertex index out of range in " + name);
                    }
                    if (scannedBounds != null && visible) {
                        var bounds = partBounds.build();
                        if (bounds != null) scannedBounds.include(bounds);
                    }
                    return new ModelData.Part(name, visible, smooth, facet, vertices, faces);
                }
                case "visible" -> visible = !line.get(1).equals("0");
                case "shading" -> smooth = !line.get(1).equals("0");
                case "facet" -> facet = number(line.get(1));
                case "scale", "rotation", "translation" -> {
                    float expected = key(line).equals("scale") ? 1 : 0;
                    for (int i = 1; i <= 3; i++) if (number(line.get(i)) != expected) {
                        throw error("Non-identity object transform in " + name + "; freeze transforms before importing");
                    }
                }
                case "mirror", "patch", "lathe" -> {
                    if (!line.get(1).equals("0")) throw error("Freeze " + line.getFirst() + " in object " + name);
                }
                case "bvertex" -> throw error("Binary BVertex is not supported; save text vertices in Metasequoia");
                case "vertex" -> {
                    int count = count(line.get(1));
                    requireBlock(line);
                    for (int i = 0; i < count; i++) {
                        List<String> v = required();
                        if (v.size() != 3) throw error("Expected XYZ vertex");
                        float x = number(v.get(0)), y = number(v.get(1)), z = number(v.get(2));
                        if (partBounds == null) vertices.add(new ModelData.Position(x, y, z));
                        else partBounds.include(x * scannedScale, y * scannedScale, z * scannedScale);
                    }
                    requireEnd();
                }
                case "face" -> {
                    if (scannedBounds != null) { skipBlock(line); break; }
                    int count = count(line.get(1));
                    requireBlock(line);
                    for (int i = 0; i < count; i++) faces.add(readFace(required()));
                    requireEnd();
                }
                default -> skipBlock(line);
            }
        }
    }

    private ModelData.Face readFace(List<String> line) throws IOException {
        int count = count(line.getFirst());
        if (count < 2 || count > 4096) throw error("Invalid face corner count: " + count);
        List<String> v = attribute(line, "v");
        if (v.size() != count) throw error("Face vertex count mismatch");
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) indices[i] = Integer.parseInt(v.get(i));
        float[] uv = floats(attribute(line, "uv"), count * 2, new float[count * 2]);
        List<String> m = attribute(line, "m"), c = attribute(line, "col");
        int[] colors = new int[count];
        if (!c.isEmpty() && c.size() != count) throw error("Face color count mismatch");
        for (int i = 0; i < count; i++) {
            int abgr = c.isEmpty() ? -1 : Integer.parseUnsignedInt(c.get(i));
            colors[i] = (abgr & 0xff00ff00) | ((abgr & 255) << 16) | ((abgr >>> 16) & 255);
        }
        return new ModelData.Face(indices, uv, colors, m.isEmpty() ? -1 : Integer.parseInt(m.getFirst()));
    }

    private List<String> attribute(List<String> line, String attribute) throws IOException {
        for (int i = 1; i + 1 < line.size(); i++) {
            if (line.get(i).equalsIgnoreCase(attribute) && line.get(i + 1).equals("(")) {
                int end = i + 2;
                while (end < line.size() && !line.get(end).equals(")")) end++;
                if (end == line.size()) throw error("Unclosed attribute " + attribute);
                return line.subList(i + 2, end);
            }
        }
        return List.of();
    }

    private float[] floats(List<String> tokens, int count, float[] fallback) throws IOException {
        if (tokens.isEmpty()) return fallback;
        if (tokens.size() != count) throw error("Attribute value count mismatch");
        float[] result = new float[count];
        for (int i = 0; i < count; i++) result[i] = number(tokens.get(i));
        return result;
    }

    private float number(String text) throws IOException {
        float value = Float.parseFloat(text);
        if (!Float.isFinite(value)) throw error("Non-finite coordinate or material value");
        return value;
    }

    private int count(String text) throws IOException {
        int count = Integer.parseInt(text);
        if (count < 0 || count > MAX_COUNT) throw error("Invalid or excessive element count: " + count);
        return count;
    }

    private void requireBlock(List<String> line) throws IOException {
        if (!line.getLast().equals("{")) throw error("Expected opening brace");
    }

    private void requireEnd() throws IOException {
        if (!required().equals(List.of("}"))) throw error("Element count mismatch or missing closing brace");
    }

    private void skipBlock(List<String> line) throws IOException {
        int depth = braceDelta(line);
        while (depth > 0) depth += braceDelta(required());
    }

    private int braceDelta(List<String> line) {
        int depth = 0;
        for (String token : line) {
            if (token.equals("{")) depth++;
            if (token.equals("}")) depth--;
        }
        return depth;
    }

    private String key(List<String> line) {
        return line.getFirst().toLowerCase(Locale.ROOT);
    }

    private String value(String token) {
        return token.startsWith("\"") ? token.substring(1, token.length() - 1) : token;
    }

    private List<String> required() throws IOException {
        List<String> line = next();
        if (line.isEmpty()) throw error("Unexpected end of file");
        return line;
    }

    private List<String> next() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            List<String> tokens = new ArrayList<>();
            for (int i = 0; i < line.length();) {
                char ch = line.charAt(i);
                if (Character.isWhitespace(ch) || ch == '\ufeff') { i++; continue; }
                if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
                if (ch == '"') {
                    int end = line.indexOf('"', i + 1);
                    if (end < 0) throw error("Unclosed quoted string");
                    // Backslashes in MQO Windows paths are literal, not Java-style escapes.
                    // Keep quotes until values are consumed, so a quoted "}" is never a block delimiter.
                    tokens.add(line.substring(i, end + 1));
                    i = end + 1;
                } else if ("{}()".indexOf(ch) >= 0) {
                    tokens.add(Character.toString(ch));
                    i++;
                } else {
                    int end = i + 1;
                    while (end < line.length() && !Character.isWhitespace(line.charAt(end))
                            && "{}()\"".indexOf(line.charAt(end)) < 0) end++;
                    tokens.add(line.substring(i, end));
                    i = end;
                }
            }
            if (!tokens.isEmpty()) return tokens;
        }
        return List.of();
    }

    private IOException error(String message) {
        return new IOException("MQO line " + lineNumber + ": " + message);
    }
}
