/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Reads one legacy script and its {@code //include} dependencies from the owning ZIP. A missing
 * Minecraft LibRenderRail include can use the built-in static-stage library; no cross-package search is performed.
 */
public final class RtmScriptSource {
    private static final Pattern INCLUDE = Pattern.compile(
            "^\\s*//\\s*include\\s*(?:<([^>]+)>|\"([^\"]+)\"|'([^']+)'|(\\S+))\\s*(?://.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_DEPTH = 16;
    private static final String LEGACY_RAIL_LIBRARY = "assets/minecraft/scripts/LibRenderRail.js";
    private static final String RAIL_LIBRARY_RESOURCE = "assets/unrealtrainmod/compat/rtm/scripts/LibRenderRail.js";
    private static final String BUILTIN_RAIL_LIBRARY = "builtin:" + RAIL_LIBRARY_RESOURCE;

    /**
     * One contiguous run of output lines copied from an archived file. {@code fileLine} is the 1-based line in
     * that file which produced {@code firstLine}, so expanded includes keep their original positions.
     */
    public record Segment(int firstLine, int lastLine, String file, int fileLine) {}

    /** 1-based location inside the archived script source. */
    public record Location(String file, int line) {
        @Override public String toString() { return file + ":" + line; }
    }

    public record Prepared(String primaryFile, String source, List<Segment> segments) {
        public Prepared {
            segments = List.copyOf(segments);
        }

        /** Map a 1-based Nashorn line number back to the included file and its original line. */
        public Location locationOf(int line) {
            for (var segment : segments) {
                if (line >= segment.firstLine() && line <= segment.lastLine()) {
                    return new Location(segment.file(), segment.fileLine() + line - segment.firstLine());
                }
            }
            return new Location(primaryFile, line);
        }
    }

    private RtmScriptSource() {}

    public static Prepared load(PackageAsset asset) throws IOException {
        var output = new ArrayList<String>();
        var segments = new ArrayList<Segment>();
        try (var zip = new ZipFile(asset.archive().toFile())) {
            var read = new ReadState(output, segments, zip, asset);
            read.file(asset.entry(), new HashSet<>(), 0);
            read.finish();
        }
        if (output.isEmpty()) throw new IOException("Script is empty: " + asset);
        return new Prepared(asset.entry(), String.join("\n", output), segments);
    }

    private static final class ReadState {
        private final List<String> output;
        private final List<Segment> segments;
        private final ZipFile zip;
        private final PackageAsset asset;
        private String segmentFile;
        private int segmentStart;
        private int segmentFileLine;

        private ReadState(List<String> output, List<Segment> segments, ZipFile zip, PackageAsset asset) {
            this.output = output;
            this.segments = segments;
            this.zip = zip;
            this.asset = asset;
        }

        private void file(String entry, Set<String> stack, int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("Script include nesting is too deep at " + entry);
            if (!stack.add(entry)) throw new IOException("Script include cycle at " + entry);
            byte[] bytes;
            if (entry.equals(BUILTIN_RAIL_LIBRARY)) {
                try (var input = RtmScriptSource.class.getResourceAsStream("/" + RAIL_LIBRARY_RESOURCE)) {
                    if (input == null) throw new IOException("Missing built-in script resource: " + entry);
                    bytes = input.readAllBytes();
                }
                ScriptDiagnostics.builtinLibrary(asset, entry);
            } else {
                var zipEntry = zip.getEntry(entry);
                if (zipEntry == null || zipEntry.isDirectory()) throw new IOException("Missing script entry: " + entry);
                try (var input = zip.getInputStream(zipEntry)) {
                    bytes = input.readAllBytes();
                }
            }
            String source = decode(bytes, entry);
            int lineNumber = 1;
            for (String raw : source.split("\n", -1)) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                var matcher = INCLUDE.matcher(line);
                if (matcher.matches()) {
                    String reference = null;
                    for (int group = 1; group <= matcher.groupCount(); group++) {
                        if (matcher.group(group) != null) {
                            reference = matcher.group(group).trim();
                            break;
                        }
                    }
                    String resolved = reference == null ? null : resolve(entry, reference);
                    if (resolved == null) {
                        throw new IOException("Included script not found from " + entry + ": " + reference);
                    }
                    // Preserve the directive's line position before splicing the included file in.
                    append(entry, lineNumber, "");
                    file(resolved, stack, depth + 1);
                    lineNumber++;
                    continue;
                }
                append(entry, lineNumber, line);
                lineNumber++;
            }
            stack.remove(entry);
        }

        private void append(String file, int lineNumber, String line) {
            if (segmentFile != null && !segmentFile.equals(file)) finishSegment();
            if (segmentFile == null) {
                segmentFile = file;
                segmentStart = output.size() + 1;
                segmentFileLine = lineNumber;
            }
            output.add(line);
        }

        private void finish() { finishSegment(); }

        private void finishSegment() {
            if (segmentFile == null) return;
            segments.add(new Segment(segmentStart, output.size(), segmentFile, segmentFileLine));
            segmentFile = null;
        }

        private String resolve(String from, String reference) {
            String value = reference.trim();
            if ((value.startsWith("<") && value.endsWith(">"))
                    || (value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isEmpty()) return null;
            value = value.replace('\\', '/');
            int slash = from.lastIndexOf('/');
            var candidates = new ArrayList<String>(4);
            candidates.add(normalize(from.substring(0, slash + 1) + value));
            int assets = from.indexOf("/assets/");
            int assetsStart = assets >= 0 ? assets + 1 : (from.startsWith("assets/") ? 0 : -1);
            if (assetsStart >= 0) {
                int namespaceStart = assetsStart + "assets/".length();
                int namespaceEnd = from.indexOf('/', namespaceStart);
                if (namespaceEnd > namespaceStart) {
                    candidates.add(normalize(from.substring(0, namespaceEnd + 1) + value));
                }
                candidates.add(normalize(from.substring(0, assetsStart) + value));
            }
            candidates.add(normalize(value));
            for (String candidate : candidates) {
                if (candidate == null) continue;
                var entry = zip.getEntry(candidate);
                if (entry != null && !entry.isDirectory()) return candidate;
            }
            // Every package-local candidate takes precedence over the one supported built-in library.
            for (String candidate : candidates) {
                if (candidate != null && (candidate.equals(LEGACY_RAIL_LIBRARY)
                        || candidate.endsWith("/" + LEGACY_RAIL_LIBRARY))) {
                    return BUILTIN_RAIL_LIBRARY;
                }
            }
            return null;
        }
    }

    private static String normalize(String value) {
        var parts = new ArrayList<String>();
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private static String decode(byte[] bytes, String entry) throws IOException {
        if (bytes.length >= 3 && (bytes[0] & 255) == 0xEF && (bytes[1] & 255) == 0xBB && (bytes[2] & 255) == 0xBF) {
            return decode(bytes, 3, StandardCharsets.UTF_8, entry);
        }
        if (bytes.length >= 2 && (bytes[0] & 255) == 0xFF && (bytes[1] & 255) == 0xFE) {
            return decode(bytes, 2, StandardCharsets.UTF_16LE, entry);
        }
        if (bytes.length >= 2 && (bytes[0] & 255) == 0xFE && (bytes[1] & 255) == 0xFF) {
            return decode(bytes, 2, StandardCharsets.UTF_16BE, entry);
        }
        try {
            return decode(bytes, 0, StandardCharsets.UTF_8, entry);
        } catch (CharacterCodingException utf8) {
            try {
                return decode(bytes, 0, Charset.forName("windows-31j"), entry);
            } catch (CharacterCodingException legacy) {
                throw new IOException("Script is neither UTF-8 nor Shift_JIS: " + entry, legacy);
            }
        }
    }

    private static String decode(byte[] bytes, int offset, Charset charset, String entry) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                .toString();
    }
}
