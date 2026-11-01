/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** Opens text MQO data directly or from the first MQO entry of an MQOZ container. */
public final class MqoModelInput {
    private MqoModelInput() {}

    public static boolean isSupported(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        return name.endsWith(".mqo") || name.endsWith(".mqoz");
    }

    /**
     * Takes ownership of the source stream. On success the caller closes the returned stream; on failure
     * this method closes the source, including the owning package when opened through PackageAsset.
     */
    public static InputStream open(InputStream source, String path) throws IOException {
        if (!path.toLowerCase(Locale.ROOT).endsWith(".mqoz")) return source;
        var zip = new ZipInputStream(source);
        try {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".mqo")) continue;
                // Keep decompression errors contextual even when they occur later inside the MQO parser.
                return new FilterInputStream(zip) {
                    @Override
                    public int read() throws IOException {
                        try { return in.read(); }
                        catch (IOException exception) { throw failure(path, exception); }
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        try { return in.read(bytes, offset, length); }
                        catch (IOException exception) { throw failure(path, exception); }
                    }
                };
            }
            throw new IOException("No .mqo entry found");
        } catch (IOException | RuntimeException | Error exception) {
            try { zip.close(); }
            catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
            if (exception instanceof Error error) throw error;
            throw failure(path, exception);
        }
    }

    private static IOException failure(String path, Throwable cause) {
        return new IOException("Cannot read MQOZ model " + path + ": " + cause.getMessage(), cause);
    }
}
