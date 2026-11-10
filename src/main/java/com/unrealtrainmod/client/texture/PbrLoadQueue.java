/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayDeque;
import net.minecraft.Util;
import org.slf4j.LoggerFactory;

/** One decoder and one completed image at a time; GL work stays at the start of a frame. */
public final class PbrLoadQueue {
    private static final ArrayDeque<Job> PENDING = new ArrayDeque<>();
    private static Job active;

    private PbrLoadQueue() {}

    static final class Job {
        private final PbrMaterial material;
        private final PbrSource source;
        private final PbrSource.Channel channel;
        private final int defaultPixel;
        private final boolean blur;
        private final int maximum;
        private boolean done, discarded;
        private NativeImage image;

        Job(PbrMaterial material, PbrSource source, PbrSource.Channel channel, int defaultPixel, boolean blur, int maximum) {
            this.material = material;
            this.source = source;
            this.channel = channel;
            this.defaultPixel = defaultPixel;
            this.blur = blur;
            this.maximum = maximum;
        }

        private void decode() {
            long start = System.nanoTime();
            NativeImage decoded = null;
            try {
                decoded = source.read(channel, defaultPixel);
                if (decoded != null) {
                    var image = decoded;
                    decoded = null;
                    decoded = TextureSizing.limit(image, maximum);
                }
            } catch (RuntimeException exception) {
                PbrStats.failure();
                LoggerFactory.getLogger(PbrLoadQueue.class).warn("Cannot decode PBR {}; using Iris default", channel, exception);
            } finally {
                PbrStats.decodedCpu(start);
                complete(decoded);
            }
        }

        private synchronized void complete(NativeImage decoded) {
            if (discarded && decoded != null) decoded.close();
            else image = decoded;
            done = true;
        }

        synchronized void discard() {
            discarded = true;
            PENDING.remove(this);
            if (image != null) { image.close(); image = null; }
        }

        private synchronized boolean discarded() { return discarded; }
        private synchronized boolean done() { return done; }
        private synchronized NativeImage take() {
            var result = image;
            image = null;
            return result;
        }
    }

    static Job enqueue(PbrMaterial material, PbrSource source, PbrSource.Channel channel, int defaultPixel, boolean blur, int maximum) {
        var job = new Job(material, source, channel, defaultPixel, blur, maximum);
        PENDING.addLast(job);
        return job;
    }

    /** Upload at most one channel per frame. A single GL upload is not interruptible. */
    public static void beginFrame(boolean enabled) {
        if (active != null) {
            if (!active.done()) return;
            var job = active;
            active = null;
            try (var image = job.take()) {
                if (!job.discarded()) job.material.upload(job.channel, image, job.blur);
            }
        }
        if (!enabled) return;
        while (!PENDING.isEmpty()) {
            var job = PENDING.removeFirst();
            if (job.discarded()) continue;
            active = job;
            Util.backgroundExecutor().execute(job::decode);
            break;
        }
    }
}
