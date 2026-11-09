/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public record ConnectorData(UUID id, ResourceLocation model, Vec3 anchor, Direction face,
                            Vec3 offset, Vec3 angles, int revision) {
    public Vec3 origin() { return anchor.add(offset); }

    /** One shared rotation for mesh, selection bounds and the wire attachment point. */
    public Quaternionf rotation() {
        var q = new Quaternionf().rotateY(radians(angles.y)).rotateX(radians(angles.x)).rotateZ(radians(angles.z));
        switch (face) {
            case DOWN -> q.rotateZ((float)Math.PI);
            case NORTH -> q.rotateY((float)Math.PI).rotateX((float)Math.PI / 2);
            case SOUTH -> q.rotateX((float)Math.PI / 2);
            case WEST -> q.rotateY(-(float)Math.PI / 2).rotateX((float)Math.PI / 2);
            case EAST -> q.rotateY((float)Math.PI / 2).rotateX((float)Math.PI / 2);
            case UP -> { }
        }
        return q;
    }

    public Vec3 transform(Vec3 local) {
        var p = new Vector3f((float)local.x, (float)local.y, (float)local.z).rotate(rotation());
        return origin().add(p.x, p.y, p.z);
    }

    public Vec3 wirePoint() {
        var definition = WiringCatalog.find(model);
        return transform(definition == null ? Vec3.ZERO : definition.wirePos());
    }

    public AABB bounds() {
        var definition = WiringCatalog.find(model);
        var box = definition == null ? com.unrealtrainmod.model.ModelBounds.MISSING : definition.bounds();
        Vec3 min = null, max = null;
        for (int i = 0; i < 8; i++) {
            var p = transform(new Vec3((i & 1) == 0 ? box.minX() : box.maxX(),
                    (i & 2) == 0 ? box.minY() : box.maxY(), (i & 4) == 0 ? box.minZ() : box.maxZ()));
            min = min == null ? p : new Vec3(Math.min(min.x, p.x), Math.min(min.y, p.y), Math.min(min.z, p.z));
            max = max == null ? p : new Vec3(Math.max(max.x, p.x), Math.max(max.y, p.y), Math.max(max.z, p.z));
        }
        return new AABB(min, max).inflate(.025);
    }

    public ConnectorData edited(Vec3 offset, Vec3 angles) {
        return new ConnectorData(id, model, anchor, face, offset, angles, revision + 1);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("Id", id); tag.putString("Model", model.toString()); tag.putString("Face", face.getName());
        putVector(tag, "Anchor", anchor); putVector(tag, "Offset", offset); putVector(tag, "Angles", angles);
        tag.putInt("Revision", revision);
        return tag;
    }

    public static ConnectorData load(CompoundTag tag) {
        var face = Direction.byName(tag.getString("Face"));
        if (face == null) throw new IllegalArgumentException("Invalid connector installation face");
        return new ConnectorData(tag.getUUID("Id"), ResourceLocation.parse(tag.getString("Model")),
                vector(tag, "Anchor"), face, vector(tag, "Offset"), vector(tag, "Angles"), tag.getInt("Revision"));
    }

    public static void putVector(CompoundTag tag, String key, Vec3 v) {
        var point = new CompoundTag(); point.putDouble("X", v.x); point.putDouble("Y", v.y); point.putDouble("Z", v.z);
        tag.put(key, point);
    }

    public static Vec3 vector(CompoundTag tag, String key) {
        var p = tag.getCompound(key);
        if (!p.contains("X", 99) || !p.contains("Y", 99) || !p.contains("Z", 99)) {
            throw new IllegalArgumentException("Missing vector: " + key);
        }
        var v = new Vec3(p.getDouble("X"), p.getDouble("Y"), p.getDouble("Z"));
        if (!Double.isFinite(v.x) || !Double.isFinite(v.y) || !Double.isFinite(v.z)) {
            throw new IllegalArgumentException("Non-finite vector: " + key);
        }
        return v;
    }

    private static float radians(double value) { return (float)Math.toRadians(value % 360); }
}
