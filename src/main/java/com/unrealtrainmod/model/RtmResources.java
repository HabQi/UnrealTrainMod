/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

/** Shared package-local resource lookup and stable name hashing. Never extracts ZIP entries. */
final class RtmResources {
    private RtmResources() {}

    static RtmAsset requireAsset(Path archive, ZipFile zip, String prefix, String reference) throws IOException {
        String normalized = reference.replace('\\', '/');
        int colon = normalized.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : normalized.substring(0, colon);
        String path = colon < 0 ? normalized : normalized.substring(colon + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || path.isEmpty() || path.startsWith("/") || path.indexOf(':') >= 0
                || List.of(path.split("/", -1)).stream().anyMatch(segment -> segment.isEmpty() || segment.equals("..") || segment.equals("."))) {
            throw new IOException("Invalid package resource path: " + reference);
        }
        String entry = prefix + "assets/" + namespace + "/" + path;
        var file = zip.getEntry(entry);
        if (file == null || file.isDirectory()) throw new IOException("Missing resource: " + entry);
        return new RtmAsset(archive, entry);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime has no SHA-256", e);
        }
    }
}
