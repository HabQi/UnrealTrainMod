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
public record IrVehicleSnapshot(Map<String, Float> values, List<Clock> clocks,
                                 Map<String, IrControlSoundState> controlSounds, boolean frontCoupled, boolean rearCoupled,
                                 long bellActivations, boolean bellPulse, long frontCouplings, long rearCouplings) {
    public static final IrVehicleSnapshot EMPTY = new IrVehicleSnapshot(Map.of(), List.of(), Map.of(), false, false, 0, false, 0, 0);
    public record Clock(boolean active, long tick) {}

    public IrVehicleSnapshot {
        values = Map.copyOf(values);
        clocks = List.copyOf(clocks);
        controlSounds = Map.copyOf(controlSounds);
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
        var sounds = new CompoundTag();
        controlSounds.forEach((key, state) -> {
            var entry = new CompoundTag();
            entry.putBoolean("Pressed", state.pressed());
            entry.putLong("Presses", state.presses());
            entry.putLong("Releases", state.releases());
            entry.putLong("Moves", state.moves());
            sounds.put(key, entry);
        });
        tag.put("ControlSounds", sounds);
        tag.putBoolean("Front", frontCoupled); tag.putBoolean("Rear", rearCoupled);
        tag.putLong("BellActivations", bellActivations);
        tag.putBoolean("BellPulse", bellPulse);
        tag.putLong("FrontCouplings", frontCouplings);
        tag.putLong("RearCouplings", rearCouplings);
        return tag;
    }

    public static IrVehicleSnapshot load(CompoundTag tag) {
        var clocks = new ArrayList<Clock>();
        var list = tag.getList("Clocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            clocks.add(new Clock(entry.getBoolean("Active"), entry.getLong("Tick")));
        }
        var sounds = new HashMap<String, IrControlSoundState>();
        var soundTag = tag.getCompound("ControlSounds");
        for (String key : soundTag.getAllKeys()) {
            var entry = soundTag.getCompound(key);
            sounds.put(key, new IrControlSoundState(entry.getBoolean("Pressed"), entry.getLong("Presses"),
                    entry.getLong("Releases"), entry.getLong("Moves")));
        }
        return new IrVehicleSnapshot(loadValues(tag.getCompound("Values")), clocks, sounds,
                tag.getBoolean("Front"), tag.getBoolean("Rear"), tag.getLong("BellActivations"), tag.getBoolean("BellPulse"),
                tag.getLong("FrontCouplings"), tag.getLong("RearCouplings"));
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
