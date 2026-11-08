/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** One immutable tracked snapshot. MIN_VALUE means an initially settled playback instance. */
public record IrVehicleSnapshot(Map<String, Float> values, List<Clock> clocks, boolean frontCoupled, boolean rearCoupled) {
    public static final IrVehicleSnapshot EMPTY = new IrVehicleSnapshot(Map.of(), List.of(), false, false);
    public record Clock(boolean active, long tick) {}

    public IrVehicleSnapshot {
        values = Map.copyOf(values);
        clocks = List.copyOf(clocks);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.put("Values", saveValues(values));
        var list = new ListTag();
        for (var clock : clocks) {
            var entry = new CompoundTag();
            entry.putBoolean("Active", clock.active()); entry.putLong("Tick", clock.tick());
            list.add(entry);
        }
        tag.put("Clocks", list);
        tag.putBoolean("Front", frontCoupled); tag.putBoolean("Rear", rearCoupled);
        return tag;
    }

    public static IrVehicleSnapshot load(CompoundTag tag) {
        var clocks = new ArrayList<Clock>();
        var list = tag.getList("Clocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            clocks.add(new Clock(entry.getBoolean("Active"), entry.getLong("Tick")));
        }
        return new IrVehicleSnapshot(loadValues(tag.getCompound("Values")), clocks, tag.getBoolean("Front"), tag.getBoolean("Rear"));
    }

    public static Map<String, Float> loadValues(CompoundTag tag) {
        var values = new HashMap<String, Float>();
        for (String name : tag.getAllKeys()) {
            float value = tag.getFloat(name);
            if (!tag.contains(name, Tag.TAG_FLOAT) || !Float.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Invalid IR control value: " + name);
            }
            values.put(name, value);
        }
        return values;
    }

    static CompoundTag saveValues(Map<String, Float> values) {
        var tag = new CompoundTag();
        values.forEach(tag::putFloat);
        return tag;
    }
}
