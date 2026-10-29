/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.gui.ListEntryImage;
import com.unrealtrainmod.client.gui.screen.RailModelScreen;
import com.unrealtrainmod.client.gui.screen.TrainModelScreen;
import com.unrealtrainmod.client.renderer.preview.PreviewRenderer;
import com.unrealtrainmod.client.renderer.preview.TrainPreviewRenderer;
import com.unrealtrainmod.client.track.ClientRailModelManager;
import com.unrealtrainmod.client.track.RailPreviewRenderer;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.track.model.RailDefinition;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;

/** Persistent IR thumbnails. Disk preparation is separate from render-thread generation and texture ownership. */
public final class ClientThumbnailManager extends SimplePreparableReloadListener<ClientThumbnailManager.Prepared> {
    public static final ClientThumbnailManager INSTANCE = new ClientThumbnailManager();
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientThumbnailManager.class);
    private static final float VEHICLE_GUI_PIXELS_PER_BLOCK = 8;
    private static final float RAIL_GUI_PIXELS_PER_BLOCK = 24;
    // Bump when framing, lighting, import appearance or alpha processing changes.
    private static final String RECIPE = "6:" + PreviewRenderer.THUMBNAIL_WIDTH + "x" + PreviewRenderer.THUMBNAIL_HEIGHT
            + ":capture-ppb=" + PreviewRenderer.THUMBNAIL_PIXELS_PER_BLOCK
            + ":vehicle-gui-ppb=" + VEHICLE_GUI_PIXELS_PER_BLOCK + ":rail-gui-ppb=" + RAIL_GUI_PIXELS_PER_BLOCK
            + ":distance=" + PreviewRenderer.THUMBNAIL_DISTANCE
            + ":vehicle=-90,22:display=0.85:rail=-180,90:ortho:trim:straight-alpha";
    public enum Kind { VEHICLE, RAIL }
    public record Key(Kind kind, ResourceLocation id) {}
    public record Entry(ResourceLocation texture, Path file) {}
    public record Prepared(long generation, Map<Key, Entry> entries, Map<Key, NativeImage> images) {}

    private final Path directory = FMLPaths.GAMEDIR.get().resolve("cache/unrealtrainmod/thumbnails/v1");
    private final Map<Key, ListEntryImage> textures = new HashMap<>();
    private final Set<Key> visible = new LinkedHashSet<>();
    private final Set<Key> failed = new HashSet<>();
    private final TrainPreviewRenderer vehicles = new TrainPreviewRenderer();
    private final RailPreviewRenderer rails = new RailPreviewRenderer();
    private final Object diskLock = new Object();
    private final ExecutorService disk = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "UTM thumbnail cache");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long generation;
    private volatile boolean ready;
    private Map<Key, Entry> entries = Map.of();

    private ClientThumbnailManager() {}

    private static int sampleScale(Kind kind) {
        return kind == Kind.VEHICLE ? PreviewRenderer.VEHICLE_THUMBNAIL_SAMPLE_SCALE : PreviewRenderer.RAIL_THUMBNAIL_SAMPLE_SCALE;
    }

    public ListEntryImage vehicleImage(VehicleDefinition definition) {
        return definition.parts().getFirst().source().format() == ModelSource.Format.IR_OBJ
                ? request(new Key(Kind.VEHICLE, definition.id()))
                : ClientButtonTextureManager.INSTANCE.texture(definition.buttonTexture());
    }

    public ListEntryImage railImage(RailDefinition definition) {
        return definition.source().format() == RailDefinition.Format.IR_OBJ
                ? request(new Key(Kind.RAIL, definition.id()))
                : ClientButtonTextureManager.INSTANCE.texture(definition.buttonTexture());
    }

    private ListEntryImage request(Key key) {
        if (!ready || !entries.containsKey(key) || failed.contains(key)) return null;
        if (!available(key)) {
            fail(key, new IllegalStateException("Client model is unavailable"));
            return null;
        }
        var image = textures.get(key);
        if (image == null) visible.add(key);
        return image;
    }

    private static boolean available(Key key) {
        if (key.kind() == Kind.VEHICLE) return ClientModelManager.INSTANCE.model(key.id()) != null;
        var model = ClientRailModelManager.INSTANCE.model(key.id());
        return model != null && model.bounds() != null;
    }

    public void onFramePre(RenderFrameEvent.Pre event) { visible.clear(); }

    public void onFramePost(RenderFrameEvent.Post event) {
        long current = generation;
        var minecraft = Minecraft.getInstance();
        if (!ready || visible.isEmpty() || minecraft.getOverlay() != null
                || !(minecraft.screen instanceof TrainModelScreen || minecraft.screen instanceof RailModelScreen)) return;
        var key = visible.iterator().next();
        var entry = entries.get(key);
        try {
            var pixels = key.kind() == Kind.VEHICLE ? vehicles.capture(ClientModelManager.INSTANCE.model(key.id()))
                    : rails.capture(ClientRailModelManager.INSTANCE.model(key.id()));
            if (!ready || generation != current) {
                pixels.close();
                return;
            }
            // upload takes ownership; disk writes retain a separate CPU copy.
            try {
                save(entry.file(), pixels, current);
            } catch (RuntimeException exception) {
                LOGGER.warn("Unable to queue thumbnail cache write for {}: {}", key, exception.getMessage());
            } catch (Error error) {
                pixels.close();
                throw error;
            }
            textures.put(key, upload(key, entry.texture(), pixels));
        } catch (RuntimeException exception) {
            fail(key, exception);
        }
    }

    private void fail(Key key, RuntimeException exception) {
        if (failed.add(key)) LOGGER.warn("Unable to generate {} thumbnail {}; using text entry: {}",
                key.kind(), key.id(), exception.getMessage());
    }

    @Override
    protected Prepared prepare(ResourceManager resources, ProfilerFiller profiler) {
        long current;
        synchronized (diskLock) {
            ready = false;
            current = ++generation;
        }
        var configs = new LinkedHashMap<Key, PackageAsset>();
        for (var definition : VehicleCatalog.all()) {
            if (definition.parts().getFirst().source().format() == ModelSource.Format.IR_OBJ) {
                configs.put(new Key(Kind.VEHICLE, definition.id()), definition.config());
            }
        }
        for (var definition : RailCatalog.all()) {
            if (definition.source().format() == RailDefinition.Format.IR_OBJ) {
                configs.put(new Key(Kind.RAIL, definition.id()), definition.config());
            }
        }
        var fingerprints = new HashMap<Path, String>();
        var preparedEntries = new LinkedHashMap<Key, Entry>();
        var images = new HashMap<Key, NativeImage>();
        try {
            for (var config : configs.entrySet()) {
                var archive = config.getValue().archive();
                if (!fingerprints.containsKey(archive)) fingerprints.put(archive, fingerprint(archive));
                String fingerprint = fingerprints.get(archive);
                if (fingerprint == null) continue;
                int sampleScale = sampleScale(config.getKey().kind());
                String digest = ResourceIds.hash(RECIPE + ":sample-scale=" + sampleScale + "\n"
                        + config.getKey().kind() + "\n" + config.getKey().id() + "\n"
                        + config.getValue().entry() + "\n" + fingerprint);
                var entry = new Entry(ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "thumbnail/" + digest),
                        directory.resolve(digest + ".png"));
                preparedEntries.put(config.getKey(), entry);
                var image = read(entry.file(), sampleScale);
                if (image != null) images.put(config.getKey(), image);
            }
            return new Prepared(current, Map.copyOf(preparedEntries), images);
        } catch (RuntimeException | Error exception) {
            images.values().forEach(NativeImage::close);
            throw exception;
        }
    }

    private static String fingerprint(Path archive) {
        try (var zip = new ZipFile(archive.toFile())) {
            var description = new StringBuilder();
            for (var entry : zip.stream().filter(value -> !value.isDirectory())
                    .sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
                description.append(entry.getName().length()).append(':').append(entry.getName()).append(':')
                        .append(entry.getCrc()).append(':').append(entry.getSize()).append('\n');
            }
            return ResourceIds.hash(description.toString());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to fingerprint thumbnail package {}: {}", archive, exception.getMessage());
            return null;
        }
    }

    private static NativeImage read(Path file, int sampleScale) {
        if (!Files.exists(file)) return null;
        try (var input = Files.newInputStream(file)) {
            var image = NativeImage.read(input);
            try {
                if (image.getWidth() > PreviewRenderer.THUMBNAIL_WIDTH * sampleScale
                        || image.getHeight() > PreviewRenderer.THUMBNAIL_HEIGHT * sampleScale) {
                    throw new IOException("Invalid cached thumbnail dimensions");
                }
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        if ((image.getPixelRGBA(x, y) >>> 24) != 0) return image;
                    }
                }
                throw new IOException("Cached thumbnail is fully transparent");
            } catch (IOException | RuntimeException | Error exception) {
                image.close();
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to read thumbnail cache {}; regenerating: {}", file, exception.getMessage());
            return null;
        }
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resources, ProfilerFiller profiler) {
        RenderSystem.assertOnRenderThread();
        var pending = new HashMap<>(prepared.images());
        try {
            if (prepared.generation() != generation) return;
            releaseResources();
            entries = prepared.entries();
            for (var key : prepared.images().keySet()) {
                var pixels = pending.remove(key);
                try {
                    textures.put(key, upload(key, entries.get(key).texture(), pixels));
                } catch (RuntimeException exception) {
                    fail(key, exception);
                }
            }
            ready = true;
            var retained = new HashSet<String>();
            entries.values().forEach(entry -> retained.add(entry.file().getFileName().toString()));
            disk.execute(() -> clean(retained, prepared.generation()));
        } catch (RuntimeException | Error exception) {
            ready = false;
            releaseResources();
            throw exception;
        } finally {
            pending.values().forEach(NativeImage::close);
        }
    }

    /** Always consumes pixels, whether DynamicTexture construction or registration succeeds or fails. */
    private static ListEntryImage upload(Key key, ResourceLocation id, NativeImage pixels) {
        float guiPixelsPerBlock = key.kind() == Kind.VEHICLE ? VEHICLE_GUI_PIXELS_PER_BLOCK * 0.85f : RAIL_GUI_PIXELS_PER_BLOCK;
        float scale = guiPixelsPerBlock / (PreviewRenderer.THUMBNAIL_PIXELS_PER_BLOCK * sampleScale(key.kind()));
        var image = new ListEntryImage(id, pixels.getWidth(), pixels.getHeight(), scale);
        int active = GlStateManager._getActiveTexture();
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        try {
            DynamicTexture texture;
            try { texture = new DynamicTexture(pixels); }
            catch (RuntimeException | Error exception) { pixels.close(); throw exception; }
            try { Minecraft.getInstance().getTextureManager().register(id, texture); }
            catch (RuntimeException | Error exception) { texture.close(); throw exception; }
            return image;
        } finally {
            RenderSystem.activeTexture(active);
            RenderSystem.bindTexture(bound);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
        }
    }

    private void save(Path file, NativeImage pixels, long expected) {
        var copy = new NativeImage(pixels.getWidth(), pixels.getHeight(), false);
        try {
            copy.copyFrom(pixels);
            disk.execute(() -> write(file, copy, expected));
        } catch (RuntimeException | Error exception) {
            copy.close();
            throw exception;
        }
    }

    private void write(Path file, NativeImage image, long expected) {
        Path temporary = null;
        try (image) {
            if (generation != expected) return;
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "thumbnail-", ".tmp");
            image.writeToFile(temporary);
            synchronized (diskLock) {
                if (generation == expected) {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to write thumbnail cache {}: {}", file, exception.getMessage());
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) { LOGGER.warn("Unable to remove thumbnail temporary file {}", temporary, exception); }
            }
        }
    }

    private void clean(Set<String> retained, long expected) {
        synchronized (diskLock) {
            if (generation != expected || !Files.isDirectory(directory)) return;
            try (var files = Files.list(directory)) {
                for (var file : files.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.png"))
                        .filter(path -> !retained.contains(path.getFileName().toString())).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Unable to clean thumbnail cache {}: {}", directory, exception.getMessage());
            }
        }
    }

    private void releaseResources() {
        var manager = Minecraft.getInstance().getTextureManager();
        textures.values().forEach(texture -> manager.release(texture.id()));
        textures.clear();
        visible.clear();
        failed.clear();
        entries = Map.of();
        try { vehicles.close(); }
        finally { rails.close(); }
    }

    public void close() {
        RenderSystem.assertOnRenderThread();
        synchronized (diskLock) {
            ready = false;
            generation++;
        }
        try { releaseResources(); }
        finally { disk.shutdown(); }
    }
}
