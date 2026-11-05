/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.resource.cache;

import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Versioned CPU data only. Each session belongs to one startup scan or preparation thread. */
public final class ModelCache implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCache.class);
    // Bump when either the binary layout or import rules change.
    private static final int VERSION = 1;
    private static final int MAGIC = 0x55544d43;
    private final Path root = FMLPaths.GAMEDIR.get().resolve("UTM_cache").resolve("v" + VERSION);
    private final Map<Path, String> digests = new HashMap<>();
    private final String phase;
    private final long started = System.nanoTime();
    private int hits, misses, invalid, failures;

    public ModelCache(String phase) { this.phase = phase; }

    @FunctionalInterface
    public interface Loader<T> { T load() throws IOException; }

    public interface Codec<T> {
        T read(Reader input, PackageAsset asset) throws IOException;
        void write(DataOutputStream output, T value) throws IOException;
    }

    /** Null disables caching for this package, including later rail preparation. */
    public String digest(Path archive) {
        Path path = archive.toAbsolutePath().normalize();
        if (digests.containsKey(path)) return digests.get(path);
        String digest = null;
        try {
            MessageDigest hash = sha256();
            try (var input = new DigestInputStream(Files.newInputStream(path), hash)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            digest = HexFormat.of().formatHex(hash.digest());
        } catch (IOException | SecurityException exception) {
            failed("hash package", path, exception);
        }
        digests.put(path, digest);
        return digest;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public <T> T load(PackageAsset asset, String kind, String options, Codec<T> codec, Loader<T> loader) throws IOException {
        return load(asset, digest(asset.archive()), kind, options, codec, loader);
    }

    /** The explicit digest identifies retained source data; never rehash it as if it were freshly parsed. */
    public <T> T load(PackageAsset asset, String digest, String kind, String options,
                      Codec<T> codec, Loader<T> loader) throws IOException {
        Path file = digest == null ? null : root
                .resolve(ResourceIds.hash(asset.archive().toAbsolutePath().normalize().toString()))
                .resolve(digest).resolve(kind).resolve(ResourceIds.hash(asset.entry() + "\0" + options) + ".bin");
        if (file != null) {
            try {
                T value = read(file, asset, codec);
                hits++;
                return value;
            } catch (NoSuchFileException exception) {
                // An ordinary miss needs no per-model log.
            } catch (IOException | IllegalArgumentException exception) {
                invalid++;
                LOGGER.warn("Ignoring invalid UTM cache {}: {}", file, exception.toString());
            } catch (SecurityException exception) {
                failed("read", file, exception);
            }
        }
        misses++;
        T value = loader.load();
        if (file != null) write(file, codec, value);
        return value;
    }

    private static <T> T read(Path file, PackageAsset asset, Codec<T> codec) throws IOException {
        long payloadSize = Files.size(file) - 16; // magic + version + CRC32C trailer
        if (payloadSize < 0) throw new IOException("Truncated cache header");
        try (var raw = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (raw.readInt() != MAGIC || raw.readInt() != VERSION) throw new IOException("Unsupported cache format");
            var limit = new PayloadInput(raw, payloadSize);
            var checksum = new CRC32C();
            var input = new Reader(limit, checksum);
            T value = codec.read(input, asset);
            if (limit.remaining != 0 || raw.readLong() != checksum.getValue() || raw.read() != -1) {
                throw new IOException("Invalid cache length or checksum");
            }
            return value;
        }
    }

    private <T> void write(Path file, Codec<T> codec, T value) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "utm-", ".tmp");
            try (var raw = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                raw.writeInt(MAGIC);
                raw.writeInt(VERSION);
                var checksum = new CRC32C();
                var output = new DataOutputStream(new CheckedOutputStream(raw, checksum));
                codec.write(output, value);
                output.flush();
                raw.writeLong(checksum.getValue());
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | SecurityException exception) {
            failed("write", file, exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | SecurityException exception) { failed("remove temporary file", temporary, exception); }
            }
        }
    }

    private void failed(String operation, Path path, Exception exception) {
        failures++;
        if (failures == 1) LOGGER.warn("Cannot {} UTM cache {}; using normal resource loading: {}",
                operation, path, exception.toString());
    }

    @Override
    public void close() {
        LOGGER.info("UTM cache [{}]: hits={}, misses={}, invalid={}, failures={}, phase={}ms, directory={}",
                phase, hits, misses, invalid, failures, (System.nanoTime() - started) / 1_000_000,
                root.getParent().toAbsolutePath().normalize());
    }

    /** Counts are checked against remaining payload bytes before allocating arrays or collections. */
    public static final class Reader extends DataInputStream {
        private final PayloadInput payload;

        private Reader(PayloadInput payload, CRC32C checksum) {
            super(new CheckedInputStream(payload, checksum));
            this.payload = payload;
        }

        public int count(int minimumBytes) throws IOException {
            int count = readInt();
            require(count >= 0 && count <= payload.remaining / minimumBytes, "Invalid element count");
            return count;
        }

        public String text() throws IOException {
            byte[] bytes = new byte[count(1)];
            readFully(bytes);
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }

        public float number() throws IOException {
            float value = readFloat();
            require(Float.isFinite(value), "Non-finite number");
            return value;
        }

        public static void require(boolean valid, String message) throws IOException {
            if (!valid) throw new IOException(message);
        }
    }

    private static final class PayloadInput extends FilterInputStream {
        private long remaining;

        private PayloadInput(InputStream input, long remaining) { super(input); this.remaining = remaining; }

        @Override
        public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = in.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = in.read(bytes, offset, (int)Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
