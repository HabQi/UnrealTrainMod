/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.geometry.TrackVector;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;

/** Cache world light per sampled block, independently of the static track geometry. */
final class TrackLighting {
    private static final int CHECK_INTERVAL = 10;
    private static final double SURFACE_OFFSET = 0.01;
    private final TrackVector origin;
    private final Long2IntOpenHashMap samples = new Long2IntOpenHashMap();
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private long nextCheck;
    private int checkedSamples;
    private int changedSamples;

    TrackLighting(TrackVector origin) {
        this.origin = origin;
        samples.defaultReturnValue(-1);
    }

    int sample(ClientLevel level, double x, double y, double z, double nx, double ny, double nz) {
        // Choose the air side of surfaces exactly on a block boundary, rather than the supporting block.
        return value(level, key(origin.x() + x, origin.y() + y, origin.z() + z, nx, ny, nz));
    }

    static long key(double x, double y, double z, double nx, double ny, double nz) {
        return BlockPos.asLong(Mth.floor(x + nx * SURFACE_OFFSET),
                Mth.floor(y + ny * SURFACE_OFFSET), Mth.floor(z + nz * SURFACE_OFFSET));
    }

    int value(ClientLevel level, long key) {
        int light = samples.get(key);
        if (light == -1) {
            light = read(level, key);
            samples.put(key, light);
        }
        return light;
    }

    /** Number of cached positions read by the last refresh; zero when the tick interval was not reached. */
    int checkedSamples() { return checkedSamples; }

    /** Cached positions whose packed light value changed in the last refresh. */
    int changedSamples() { return changedSamples; }

    boolean refresh(ClientLevel level) {
        long time = level.getGameTime();
        if (time < nextCheck) {
            checkedSamples = 0;
            changedSamples = 0;
            return false;
        }
        nextCheck = time + CHECK_INTERVAL;
        int changed = 0;
        for (var entry : samples.long2IntEntrySet()) {
            int light = read(level, entry.getLongKey());
            if (light != entry.getIntValue()) {
                entry.setValue(light);
                changed++;
            }
        }
        checkedSamples = samples.size();
        changedSamples = changed;
        return changed > 0;
    }

    private int read(ClientLevel level, long key) {
        position.set(key);
        return LightTexture.pack(level.getBrightness(LightLayer.BLOCK, position),
                level.getBrightness(LightLayer.SKY, position));
    }
}
