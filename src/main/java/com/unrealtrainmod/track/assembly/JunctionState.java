/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Complete, immutable positions of an assembly's controls. */
public record JunctionState(Map<String, SwitchPosition> controls) {
    public JunctionState { controls = Map.copyOf(controls); }

    public JunctionState withControl(String id, SwitchPosition position) {
        if (!controls.containsKey(id)) throw new IllegalArgumentException("Unknown track control: " + id);
        var updated = new LinkedHashMap<>(controls);
        updated.put(id, position);
        return new JunctionState(updated);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        controls.forEach((id, position) -> tag.putString(id, position.name()));
        return tag;
    }

    public static JunctionState load(CompoundTag tag) {
        var controls = new LinkedHashMap<String, SwitchPosition>();
        for (String id : tag.getAllKeys()) {
            if (id.isBlank() || !tag.contains(id, Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid track control");
            controls.put(id, SwitchPosition.valueOf(tag.getString(id)));
        }
        return new JunctionState(controls);
    }
}
