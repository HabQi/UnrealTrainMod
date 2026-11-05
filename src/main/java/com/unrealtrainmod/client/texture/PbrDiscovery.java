/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.slf4j.LoggerFactory;

/** One exact ZIP-entry index per archive in this preparation; no auxiliary image decoding. */
public final class PbrDiscovery {
    private final Map<Path, Set<String>> archives = new HashMap<>();
    private final Map<PbrSource.FileKey, PbrSource.Files> sources = new HashMap<>();
    private final Set<PackageAsset> found = new HashSet<>();

    public PbrSource.Files find(PackageAsset base, int width, int height) throws IOException {
        var names = archives.get(base.archive());
        if (names == null) {
            try (var zip = new ZipFile(base.archive().toFile())) {
                names = new HashSet<>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.isDirectory()) names.add(entry.getName());
                }
            }
            archives.put(base.archive(), names);
        }
        String path = base.entry();
        int dot = path.lastIndexOf('.');
        String stem = dot > path.lastIndexOf('/') ? path.substring(0, dot) : path;
        var normal = sibling(base, names, stem + PbrSource.Channel.NORMAL.suffix);
        var specular = sibling(base, names, stem + PbrSource.Channel.SPECULAR.suffix);
        if (normal == null && specular == null) return null;
        var key = new PbrSource.FileKey(normal, specular, width, height);
        return sources.computeIfAbsent(key, PbrSource.Files::new);
    }

    private PackageAsset sibling(PackageAsset base, Set<String> names, String path) {
        if (!names.contains(path)) return null;
        var asset = new PackageAsset(base.archive(), path);
        found.add(asset);
        return asset;
    }

    public void log(String scene) {
        LoggerFactory.getLogger("UnrealTrainMod/PBR").info(
                "PBR discovery [{}]: archives={} auxiliaryFiles={} (metadata only; pixels load on demand)",
                scene, archives.size(), found.size());
    }
}
