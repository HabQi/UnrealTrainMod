/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.vehicle.VehicleRuntimeState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** Server-owned values and edge clocks; no animation matrices are loaded on the server. */
public final class IrVehicleState {
    private IrVehicleDefinition definition = IrVehicleDefinition.NONE;
    private final Map<String, Float> values = new HashMap<>();
    private final Map<String, Long> releases = new HashMap<>();
    private final ArrayList<IrVehicleSnapshot.Clock> clocks = new ArrayList<>();
    private boolean dirty, front, rear;

    public void bind(IrVehicleDefinition next, VehicleRuntimeState runtime, boolean coupledFront, boolean coupledRear) {
        if (definition == next) return;
        definition = next;
        front = coupledFront; rear = coupledRear;
        releases.clear(); clocks.clear();
        next.controls().forEach((group, control) -> {
            if (control.readOnly() || control.type() == IrControlDefinition.Type.PRESS) values.remove(group);
        });
        for (var animation : next.animations()) {
            clocks.add(new IrVehicleSnapshot.Clock(animation.value(next, values, runtime, front, rear) >= 0.95f, Long.MIN_VALUE));
        }
        dirty = true;
    }

    public boolean set(String group, float value, boolean pulse, long tick) {
        var control = definition.controls().get(group);
        if (control == null) throw new IllegalArgumentException("Unknown IR control group: " + group);
        if (control.readOnly()) throw new IllegalArgumentException("IR driving control is read-only: " + group);
        if (!Float.isFinite(value) || value < 0 || value > 1
                || (control.type() != IrControlDefinition.Type.CONTINUOUS && value != 0 && value != 1)) {
            throw new IllegalArgumentException("Invalid value for IR control: " + group);
        }
        if (values.getOrDefault(group, control.defaultValue()) == value) return false;
        values.put(group, value);
        if (value == 1 && (pulse || control.type() == IrControlDefinition.Type.PRESS)) releases.put(group, tick + 1);
        else releases.remove(group);
        dirty = true;
        return true;
    }

    public boolean update(VehicleRuntimeState runtime, boolean coupledFront, boolean coupledRear, long tick) {
        if (front != coupledFront || rear != coupledRear) dirty = true;
        front = coupledFront; rear = coupledRear;
        var iterator = releases.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue() > tick) continue;
            values.put(entry.getKey(), 0f); iterator.remove(); dirty = true;
        }
        for (int i = 0; i < clocks.size(); i++) {
            boolean active = definition.animations().get(i).value(definition, values, runtime, front, rear) >= 0.95f;
            if (active != clocks.get(i).active()) {
                clocks.set(i, new IrVehicleSnapshot.Clock(active, tick)); dirty = true;
            }
        }
        boolean changed = dirty;
        dirty = false;
        return changed;
    }

    public IrVehicleSnapshot snapshot() { return new IrVehicleSnapshot(values, clocks, front, rear); }

    public CompoundTag saveValues() {
        var saved = new HashMap<>(values);
        definition.controls().forEach((group, control) -> {
            if (control.readOnly() || control.type() == IrControlDefinition.Type.PRESS) saved.remove(group);
        });
        releases.keySet().forEach(group -> saved.put(group, 0f));
        return IrVehicleSnapshot.saveValues(saved);
    }

    public void loadValues(CompoundTag tag) {
        clear();
        values.putAll(IrVehicleSnapshot.loadValues(tag));
        dirty = true;
    }

    public void clear() {
        definition = IrVehicleDefinition.NONE;
        values.clear(); releases.clear(); clocks.clear();
        front = rear = false;
        dirty = true;
    }
}
