/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.resource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** An exact, case-sensitive entry in its owning package. No files are extracted. */
public record PackageAsset(Path archive, String entry) {
    public InputStream open() throws IOException {
        var zip = new ZipFile(archive.toFile());
        try {
            var file = zip.getEntry(entry);
            if (file == null) throw new IOException("Missing ZIP entry: " + this);
            return new FilterInputStream(zip.getInputStream(file)) {
                @Override
                public void close() throws IOException {
                    try { super.close(); }
                    finally { zip.close(); }
                }
            };
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }
}
