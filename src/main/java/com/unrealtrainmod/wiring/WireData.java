/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Endpoint order is retained for directional legacy scripts. */
public record WireData(UUID id, UUID first, UUID second, ResourceLocation model) {
    public boolean joins(UUID a, UUID b) { return first.equals(a) && second.equals(b) || first.equals(b) && second.equals(a); }
    public boolean touches(UUID node) { return first.equals(node) || second.equals(node); }
    public net.minecraft.world.phys.AABB bounds(ConnectorData a, ConnectorData b) {
        var start = a.wirePoint(); var end = b.wirePoint();
        var definition = WiringCatalog.find(model);
        double margin = 2, sag = 0, yOffset = 0;
        if (definition != null) {
            var box = definition.bounds();
            margin = Math.max(margin, Math.max(Math.max(Math.abs(box.minX()), Math.abs(box.maxX())),
                    Math.max(Math.max(Math.abs(box.minY()), Math.abs(box.maxY())), Math.max(Math.abs(box.minZ()), Math.abs(box.maxZ())))));
            yOffset = definition.yOffset();
            var target = end.subtract(start); double horizontal = Math.hypot(target.x, target.z), length = target.length();
            if (length > 1e-6) sag = definition.deflection() * horizontal / length
                    / Math.pow(1 + definition.lengthCoefficient(), horizontal) * horizontal * horizontal / 4;
        }
        var span = new net.minecraft.world.phys.AABB(start, end).inflate(margin);
        return new net.minecraft.world.phys.AABB(span.minX, span.minY - sag + Math.min(0, yOffset), span.minZ,
                span.maxX, span.maxY + Math.max(0, yOffset), span.maxZ);
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putUUID("Id", id); tag.putUUID("First", first); tag.putUUID("Second", second);
        tag.putString("Model", model.toString()); return tag;
    }
    public static WireData load(CompoundTag tag) {
        var value = new WireData(tag.getUUID("Id"), tag.getUUID("First"), tag.getUUID("Second"),
                ResourceLocation.parse(tag.getString("Model")));
        if (value.first.equals(value.second)) throw new IllegalArgumentException("A wire needs two connectors");
        return value;
    }
}
