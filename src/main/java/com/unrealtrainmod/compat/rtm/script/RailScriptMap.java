/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/** Read-only path geometry in the legacy Z/X order and degree units. */
public final class RailScriptMap {
    /** Legacy scripts subtract this offset before drawing a model whose rail top is at Y=0. */
    static final double HEIGHT_OFFSET = 0.0625;

    public static final class Position {
        public final double posX, posY, posZ;
        public final int blockX, blockY, blockZ;
        public final int direction = 0;

        private Position(TrackPose pose) {
            var p = pose.position();
            posX = p.x(); posY = p.y() + HEIGHT_OFFSET; posZ = p.z();
            blockX = Mth.floor(posX); blockY = Mth.floor(posY); blockZ = Mth.floor(posZ);
        }

        /** The legacy tile's origin may be virtual; map endpoints still expose their actual positions. */
        Position(BlockPos anchor, double y) {
            posX = anchor.getX() + 0.5; posY = y + HEIGHT_OFFSET; posZ = anchor.getZ() + 0.5;
            blockX = anchor.getX(); blockY = anchor.getY(); blockZ = anchor.getZ();
        }
    }

    private final TrackGeometry geometry;
    private final int cachedSplit;
    private final TrackPose[] samples;

    RailScriptMap(TrackGeometry geometry) { this(geometry, false); }

    RailScriptMap(TrackGeometry geometry, boolean animated) {
        this.geometry = geometry;
        cachedSplit = Math.max(1, (int)Math.floor(geometry.getLength() * 2));
        samples = animated ? new TrackPose[cachedSplit + 1] : null;
        if (animated) for (int i = 0; i <= cachedSplit; i++) samples[i] = geometry.sample(geometry.getLength() * i / cachedSplit);
    }

    public double getLength() { return geometry.getLength(); }
    public Position getStartRP() { return new Position(sample(cachedSplit, 0)); }
    public Position getEndRP() { return new Position(sample(cachedSplit, cachedSplit)); }

    TrackPose sample(int split, double index) {
        if (split < 0 || !Double.isFinite(index)) throw new IllegalArgumentException("Invalid rail sampling parameters");
        if (split == 0) return samples == null ? geometry.sample(0) : samples[0];
        if (samples != null && split == cachedSplit && index == Math.rint(index)) return samples[(int)Math.clamp(index, 0, cachedSplit)];
        return geometry.sample(getLength() * (index / split));
    }

    public double[] getRailPos(int split, double index) {
        var p = sample(split, index).position();
        return new double[]{p.z(), p.x()};
    }

    public double getRailHeight(int split, double index) { return sample(split, index).position().y() + HEIGHT_OFFSET; }

    public float getRailYaw(int split, double index) {
        var t = sample(split, index).tangent();
        return (float)Math.toDegrees(Math.atan2(t.x(), t.z()));
    }

    public float getRailRotation(int split, double index) { return getRailYaw(split, index); }

    public float getRailPitch(int split, double index) {
        var t = sample(split, index).tangent();
        return (float)Math.toDegrees(Math.atan2(t.y(), Math.hypot(t.x(), t.z())));
    }

    /** Native normal-track geometry currently has no cant. */
    public float getRailRoll(int split, double index) { sample(split, index); return 0; }
    public float getCant(int split, double index) { return getRailRoll(split, index); }

    TrackGeometry geometry() { return geometry; }
}
