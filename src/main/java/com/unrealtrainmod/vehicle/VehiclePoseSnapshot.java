/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/** Network-only motion in blocks/degrees per complete server tick. */
public record VehiclePoseSnapshot(long tick, long revision, VehiclePose pose, Motion motion, boolean predicting) {
    public record Motion(double x, double y, double z, double yaw, double pitch) {
        public static final Motion ZERO = new Motion(0, 0, 0, 0, 0);

        public static Motion between(VehiclePose.Body from, VehiclePose.Body to) {
            return new Motion(to.position().x() - from.position().x(), to.position().y() - from.position().y(),
                    to.position().z() - from.position().z(), Mth.wrapDegrees((double)to.yaw() - from.yaw()),
                    (double)to.pitch() - from.pitch());
        }

        public Motion add(Motion other) {
            return new Motion(x + other.x, y + other.y, z + other.z, yaw + other.yaw, pitch + other.pitch);
        }

        public Motion scale(double scale) {
            return new Motion(x * scale, y * scale, z * scale, yaw * scale, pitch * scale);
        }

        public VehiclePose.Body apply(VehiclePose.Body body) {
            return new VehiclePose.Body(new TrackVector(body.position().x() + x, body.position().y() + y,
                    body.position().z() + z), (float)(body.yaw() + yaw), (float)(body.pitch() + pitch));
        }
    }

    public CompoundTag save() {
        var tag = pose.save();
        tag.putLong("PoseTick", tick);
        tag.putLong("PoseRevision", revision);
        tag.putBoolean("Predict", predicting);
        tag.putDouble("MotionX", motion.x());
        tag.putDouble("MotionY", motion.y());
        tag.putDouble("MotionZ", motion.z());
        tag.putDouble("MotionYaw", motion.yaw());
        tag.putDouble("MotionPitch", motion.pitch());
        return tag;
    }

    public static VehiclePoseSnapshot load(CompoundTag tag) {
        if (!tag.contains("PoseTick", Tag.TAG_LONG) || !tag.contains("PoseRevision", Tag.TAG_LONG)
                || !tag.contains("Predict", Tag.TAG_BYTE)) {
            throw new IllegalArgumentException("Missing pose snapshot timing");
        }
        var motion = new Motion(number(tag, "MotionX"), number(tag, "MotionY"), number(tag, "MotionZ"),
                number(tag, "MotionYaw"), number(tag, "MotionPitch"));
        boolean predicting = tag.getBoolean("Predict");
        return new VehiclePoseSnapshot(tag.getLong("PoseTick"), tag.getLong("PoseRevision"), VehiclePose.load(tag),
                predicting ? motion : Motion.ZERO, predicting);
    }

    private static double number(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_DOUBLE) || !Double.isFinite(tag.getDouble(key))) {
            throw new IllegalArgumentException("Invalid pose motion " + key);
        }
        return tag.getDouble(key);
    }
}
