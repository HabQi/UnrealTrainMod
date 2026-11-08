/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.mojang.blaze3d.audio.SoundBuffer;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.Util;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-thread registry. The vanilla buffer cache owns uploaded OpenAL buffers. */
public final class PackageSoundLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackageSoundLibrary.class);
    private static final Map<ResourceLocation, Clip> CLIPS = new HashMap<>();
    private static long generation;

    record Decoded(SoundBuffer buffer, double ticks) {}

    record Clip(ResourceLocation id, CompletableFuture<Decoded> decoded) {
        Decoded ready() {
            if (!decoded.isDone() || decoded.isCompletedExceptionally()) return null;
            return decoded.join();
        }
    }

    private PackageSoundLibrary() {}

    static Clip clip(PackageAsset asset) {
        String key = asset.archive().toAbsolutePath().normalize() + "!" + asset.entry();
        var id = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "package_sound/" + ResourceIds.hash(key));
        return CLIPS.computeIfAbsent(Sound.SOUND_LISTER.idToFile(id), ignored -> new Clip(id,
                CompletableFuture.supplyAsync(() -> decode(asset), Util.nonCriticalIoPool())));
    }

    /** Null means that the original resource provider must handle this resource. */
    public static CompletableFuture<SoundBuffer> buffer(ResourceLocation path) {
        var clip = CLIPS.get(path);
        return clip == null ? null : clip.decoded().thenApply(Decoded::buffer);
    }

    public static long generation() { return generation; }

    /** Called together with SoundBufferLibrary.clear, including audio-device changes. */
    public static void invalidate() {
        generation++;
        CLIPS.clear();
    }

    private static Decoded decode(PackageAsset asset) {
        try (var input = asset.open(); var stream = new JOrbisAudioStream(input)) {
            var format = stream.getFormat();
            int channels = format.getChannels();
            if (channels != 1 && channels != 2) throw new IOException("expected mono or stereo OGG");
            var pcm = stream.readAll().order(ByteOrder.LITTLE_ENDIAN);
            int frames = pcm.remaining() / (2 * channels);
            if (frames == 0) throw new IOException("empty OGG audio");
            ByteBuffer mono = pcm;
            if (channels == 2) {
                mono = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
                while (pcm.remaining() >= 4) {
                    int left = pcm.getShort(), right = pcm.getShort();
                    mono.putShort((short)((left + right) / 2));
                }
                mono.flip();
            }
            var output = new AudioFormat(format.getSampleRate(), 16, 1, true, false);
            return new Decoded(new SoundBuffer(mono, output), frames * 20.0 / format.getSampleRate());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to decode vehicle sound {}: {}", asset, exception.getMessage());
            throw new CompletionException(exception);
        }
    }
}
