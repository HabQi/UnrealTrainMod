/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** One read-only ZIP session. Streams must be closed before their archive. */
public final class PackageArchive implements AutoCloseable {
    private final Path path;
    private final ZipFile zip;
    private final List<String> entries;

    public PackageArchive(Path path) throws IOException {
        this.path = path;
        zip = new ZipFile(path.toFile());
        try {
            entries = zip.stream().filter(entry -> !entry.isDirectory())
                    .map(java.util.zip.ZipEntry::getName).sorted().toList();
        } catch (RuntimeException | Error e) {
            zip.close();
            throw e;
        }
    }

    public Path path() { return path; }
    public List<String> entries() { return entries; }

    public boolean contains(String entry) {
        var file = zip.getEntry(entry);
        return file != null && !file.isDirectory();
    }

    public InputStream open(String entry) throws IOException {
        var file = zip.getEntry(entry);
        if (file == null || file.isDirectory()) throw new IOException("Missing ZIP resource: " + path + "!" + entry);
        return zip.getInputStream(file);
    }

    public PackageAsset resolve(String prefix, String reference) throws IOException {
        String normalized = reference.replace('\\', '/');
        int colon = normalized.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : normalized.substring(0, colon);
        String resourcePath = colon < 0 ? normalized : normalized.substring(colon + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || resourcePath.isEmpty() || resourcePath.startsWith("/")
                || resourcePath.indexOf(':') >= 0 || List.of(resourcePath.split("/", -1)).stream()
                        .anyMatch(segment -> segment.isEmpty() || segment.equals("..") || segment.equals("."))) {
            throw new IOException("Invalid package resource path: " + reference);
        }
        String entry = prefix + "assets/" + namespace + "/" + resourcePath;
        if (!contains(entry)) throw new IOException("Missing resource: " + entry);
        return new PackageAsset(path, entry);
    }

    @Override
    public void close() throws IOException { zip.close(); }
}
