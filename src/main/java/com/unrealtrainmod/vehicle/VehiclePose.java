/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.track.geometry.TrackVector;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

/** Immutable body frame and rail-datum bogie frames relative to that body. No mutable math is retained. */
public record VehiclePose(Body body, Bogie frontBogie, Bogie rearBogie) {
    public record Body(TrackVector position, float yaw, float pitch) {
        public Quaternionf rotation() { return rotation(yaw, pitch); }

        public Body interpolate(Body target, double fraction) {
            return new Body(lerp(position, target.position, fraction),
                    (float) Mth.rotLerp(fraction, (double) yaw, target.yaw),
                    (float) Mth.lerp(fraction, (double) pitch, target.pitch));
        }

        public static Quaternionf rotation(float yaw, float pitch) {
            return new Quaternionf().rotationY((float)Math.toRadians(-yaw)).rotateX((float)Math.toRadians(pitch));
        }
    }

    /** Translation in body coordinates and a unit quaternion relative to body rotation. */
    public record Bogie(TrackVector position, float qx, float qy, float qz, float qw) {
        public static Bogie of(TrackVector position, Quaternionf rotation) {
            return new Bogie(position, rotation.x, rotation.y, rotation.z, rotation.w);
        }

        public Quaternionf rotation() { return new Quaternionf(qx, qy, qz, qw); }

        private Bogie interpolate(Bogie target, double fraction) {
            return of(lerp(position, target.position, fraction), rotation().slerp(target.rotation(), (float)fraction));
        }

        private CompoundTag save() {
            var tag = new CompoundTag();
            savePosition(tag, position);
            tag.putFloat("Qx", qx); tag.putFloat("Qy", qy);
            tag.putFloat("Qz", qz); tag.putFloat("Qw", qw);
            return tag;
        }

        private static Bogie load(CompoundTag tag) {
            var position = loadPosition(tag);
            var q = new Quaternionf((float)number(tag, "Qx"), (float)number(tag, "Qy"),
                    (float)number(tag, "Qz"), (float)number(tag, "Qw"));
            float squared = q.lengthSquared();
            if (!Float.isFinite(squared) || squared < 1e-12f) {
                throw new IllegalArgumentException("Invalid bogie rotation");
            }
            return of(position, q.normalize());
        }
    }

    /** Source-local geometry to body-local geometry; static offsets are applied exactly once. */
    public Bogie partTransform(VehicleDefinition.Instance part) {
        var offset = part.offset();
        if (part.role() == VehiclePartRole.BODY) {
            return new Bogie(new TrackVector(offset.x(), offset.y(), offset.z()), 0, 0, 0, 1);
        }
        var bogie = part.role() == VehiclePartRole.FRONT_BOGIE ? frontBogie : rearBogie;
        var pivot = part.pivot();
        // Longitudinal placement comes from motionSpec sampling, not the static assembly's Z offset.
        var local = new TrackVector(offset.x() - pivot.x(), offset.y() - pivot.y(), -pivot.z());
        var rotation = bogie.rotation();
        return Bogie.of(add(bogie.position, rotate(rotation, local)), rotation);
    }

    public VehiclePose interpolate(VehiclePose target, double fraction) {
        return new VehiclePose(body.interpolate(target.body, fraction), frontBogie.interpolate(target.frontBogie, fraction),
                rearBogie.interpolate(target.rearBogie, fraction));
    }

    /** All visible components and the entity-owning origin participate in area checks and selection. */
    public AABB bounds(VehicleDefinition definition) {
        var p = body.position;
        AABB result = new AABB(p.x(), p.y(), p.z(), p.x(), p.y(), p.z());
        var rotation = body.rotation();
        for (var part : definition.parts()) {
            var local = partTransform(part);
            result = result.minmax(transformedBounds(part.bounds(), body, local, rotation));
        }
        return result;
    }

    public static AABB staticBounds(ModelBounds bounds, Body body) {
        return transformedBounds(bounds, body, new Bogie(new TrackVector(0, 0, 0), 0, 0, 0, 1), body.rotation());
    }

    private static AABB transformedBounds(ModelBounds bounds, Body body, Bogie local, Quaternionf rotation) {
        var localRotation = local.rotation();
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int corner = 0; corner < 8; corner++) {
            var vertex = new TrackVector((corner & 1) == 0 ? bounds.minX() : bounds.maxX(),
                    (corner & 2) == 0 ? bounds.minY() : bounds.maxY(),
                    (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ());
            var world = add(body.position, rotate(rotation, add(local.position, rotate(localRotation, vertex))));
            minX = Math.min(minX, world.x()); maxX = Math.max(maxX, world.x());
            minY = Math.min(minY, world.y()); maxY = Math.max(maxY, world.y());
            minZ = Math.min(minZ, world.z()); maxZ = Math.max(maxZ, world.z());
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public CompoundTag saveBogies() {
        var tag = new CompoundTag();
        tag.put("Front", frontBogie.save());
        tag.put("Rear", rearBogie.save());
        return tag;
    }

    public CompoundTag save() {
        var tag = saveBogies();
        savePosition(tag, body.position);
        tag.putFloat("Yaw", body.yaw);
        tag.putFloat("Pitch", body.pitch);
        return tag;
    }

    public static VehiclePose load(CompoundTag tag) {
        float yaw = (float)number(tag, "Yaw"), pitch = (float)number(tag, "Pitch");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Invalid body angles");
        return loadBogies(tag, new Body(loadPosition(tag), yaw, pitch));
    }

    public static VehiclePose loadBogies(CompoundTag tag, Body body) {
        if (!tag.contains("Front", Tag.TAG_COMPOUND) || !tag.contains("Rear", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Bogie pose requires Front and Rear frames");
        }
        return new VehiclePose(body, Bogie.load(tag.getCompound("Front")), Bogie.load(tag.getCompound("Rear")));
    }

    private static void savePosition(CompoundTag tag, TrackVector p) {
        tag.putDouble("X", p.x()); tag.putDouble("Y", p.y()); tag.putDouble("Z", p.z());
    }

    private static TrackVector loadPosition(CompoundTag tag) {
        return new TrackVector(number(tag, "X"), number(tag, "Y"), number(tag, "Z"));
    }

    private static double number(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException("Missing pose field " + key);
        double value = tag.getDouble(key);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite pose field " + key);
        return value;
    }

    static TrackVector add(TrackVector a, TrackVector b) {
        return new TrackVector(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }

    static TrackVector subtract(TrackVector a, TrackVector b) {
        return new TrackVector(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static TrackVector lerp(TrackVector a, TrackVector b, double fraction) {
        return new TrackVector(Mth.lerp(fraction, a.x(), b.x()), Mth.lerp(fraction, a.y(), b.y()),
                Mth.lerp(fraction, a.z(), b.z()));
    }

    /** Double position arithmetic avoids truncating world coordinates to float during rotation. */
    static TrackVector rotate(Quaternionf q, TrackVector p) {
        double tx = 2 * (q.y * p.z() - q.z * p.y());
        double ty = 2 * (q.z * p.x() - q.x * p.z());
        double tz = 2 * (q.x * p.y() - q.y * p.x());
        return new TrackVector(p.x() + q.w * tx + q.y * tz - q.z * ty,
                p.y() + q.w * ty + q.z * tx - q.x * tz,
                p.z() + q.w * tz + q.x * ty - q.y * tx);
    }
}
