/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;

/** Shared text and logical resource-path handling for OBJ and MTL. Does not own the reader. */
final class WavefrontReader {
    private final BufferedReader reader;
    private final String sourcePath;
    private int lineNumber;
    private String line;

    WavefrontReader(Reader reader, String sourcePath) throws IOException {
        this.reader = new BufferedReader(reader);
        try {
            this.sourcePath = normalize(sourcePath);
        } catch (IllegalArgumentException e) {
            throw new IOException(sourcePath + ":0: " + e.getMessage(), e);
        }
    }

    String[] next() throws IOException {
        while (true) {
            try {
                line = reader.readLine();
            } catch (IOException e) {
                throw new IOException(sourcePath + ":" + (lineNumber + 1) + ": Unable to read text", e);
            }
            if (line == null) return null;
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\ufeff")) line = line.substring(1);
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.strip();
            if (!line.isEmpty()) return line.split("\\s+");
        }
    }

    String argument() throws IOException {
        int start = 0;
        while (start < line.length() && !Character.isWhitespace(line.charAt(start))) start++;
        String value = line.substring(start).strip();
        if (value.isEmpty()) throw error("Missing argument");
        return value;
    }

    void count(String[] fields, int min, int max) throws IOException {
        if (fields.length < min || fields.length > max) {
            throw error("Unexpected argument count for " + fields[0]);
        }
    }

    float number(String value) throws IOException {
        try {
            float result = Float.parseFloat(value);
            if (Float.isFinite(result)) return result;
        } catch (NumberFormatException e) {
            throw error("Invalid number: " + value);
        }
        throw error("Non-finite number: " + value);
    }

    int integer(String value) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw error("Invalid integer: " + value);
        }
    }

    String resolve(String reference) throws IOException {
        String path = reference.replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) {
            throw error("Expected a relative resource path: " + reference);
        }
        String parent = sourcePath.substring(0, sourcePath.lastIndexOf('/') + 1);
        try {
            return normalize(parent + path);
        } catch (IllegalArgumentException e) {
            throw error(e.getMessage());
        }
    }

    private static String normalize(String reference) {
        String path = reference.replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Expected a relative resource path: " + reference);
        }
        var segments = new ArrayList<String>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) throw new IllegalArgumentException("Resource path escapes root: " + reference);
                segments.removeLast();
            } else {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("Resource path resolves to root: " + reference);
        return String.join("/", segments);
    }

    int lineNumber() { return lineNumber; }

    IOException error(String message) { return error(lineNumber, message); }

    IOException error(int atLine, String message) {
        return new IOException(sourcePath + ":" + atLine + ": " + message);
    }
}
