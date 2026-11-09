/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.vehicle.VehicleRuntimeState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Server-owned values and edge clocks; no animation matrices are loaded on the server. */
public final class IrVehicleState {
    private enum BellMode { OFF, SUSTAINED, PULSE }
    private IrVehicleDefinition definition = IrVehicleDefinition.NONE;
    private final Map<String, Float> values = new HashMap<>();
    private final Map<String, Long> releases = new HashMap<>();
    private final Map<UUID, String> heldPresses = new HashMap<>();
    private final ArrayList<IrVehicleSnapshot.Clock> clocks = new ArrayList<>();
    private final Map<String, IrControlSoundState> soundStates = new HashMap<>();
    private final Map<UUID, String> soundOwners = new HashMap<>();
    private final Map<String, Float> moveValues = new HashMap<>();
    private boolean dirty, front, rear;
    private boolean bellActive, bellPulse;
    private long bellActivations, frontCouplings, rearCouplings;

    public void bind(IrVehicleDefinition next, VehicleRuntimeState runtime, boolean coupledFront, boolean coupledRear) {
        if (definition == next) return;
        definition = next;
        front = coupledFront; rear = coupledRear;
        releases.clear(); heldPresses.clear(); clocks.clear();
        soundStates.clear(); soundOwners.clear(); moveValues.clear();
        next.controls().forEach((group, control) -> {
            if (control.readOnly() || control.type() == IrControlDefinition.Type.PRESS) values.remove(group);
        });
        bellActive = bellMode(runtime) != BellMode.OFF;
        bellPulse = false;
        bellActivations = frontCouplings = rearCouplings = 0;
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
        if (value == 1 && !heldPresses.containsValue(group)
                && (pulse || control.type() == IrControlDefinition.Type.PRESS)) releases.put(group, tick + 1);
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
        var mode = bellMode(runtime);
        boolean nextBell = mode != BellMode.OFF;
        if (nextBell && !bellActive) {
            bellPulse = mode == BellMode.PULSE;
            bellActivations++;
            dirty = true;
        }
        bellActive = nextBell;
        for (int i = 0; i < clocks.size(); i++) {
            boolean active = definition.animations().get(i).value(definition, values, runtime, front, rear) >= 0.95f;
            if (active != clocks.get(i).active()) {
                clocks.set(i, new IrVehicleSnapshot.Clock(active, tick)); dirty = true;
            }
        }
        for (var entry : moveValues.entrySet()) {
            var sound = definition.sounds().controls().get(entry.getKey());
            float value = definition.value(sound.group(), values, runtime, front, rear);
            if (Math.abs(value - entry.getValue()) > sound.movePercent()) {
                var state = soundStates.get(entry.getKey());
                soundStates.put(entry.getKey(), new IrControlSoundState(true, state.presses(), state.releases(), state.moves() + 1));
                entry.setValue(value);
                dirty = true;
            }
        }
        boolean changed = dirty;
        dirty = false;
        return changed;
    }

    public void holdPress(UUID owner, String group, long tick) {
        releasePress(owner, tick);
        set(group, 1, false, tick);
        releases.remove(group);
        heldPresses.put(owner, group);
    }

    public void releasePress(UUID owner, long tick) {
        String group = heldPresses.remove(owner);
        if (group != null && !heldPresses.containsValue(group)) set(group, 0, false, tick);
    }

    /** A driver session owns at most one held component; multiple drivers may hold the same component. */
    public void pressSound(UUID owner, String component, VehicleRuntimeState runtime) {
        if (component != null && component.equals(soundOwners.get(owner))) return;
        releaseSound(owner);
        if (component == null) return;
        var state = soundStates.getOrDefault(component, IrControlSoundState.IDLE);
        soundOwners.put(owner, component);
        if (state.pressed()) return;
        var sound = definition.sounds().controls().get(component);
        if (sound.move() != null && sound.movePercent() != null) {
            moveValues.put(component, definition.value(sound.group(), values, runtime, front, rear));
        }
        soundStates.put(component, new IrControlSoundState(true, state.presses() + 1, state.releases(), state.moves()));
        dirty = true;
    }

    public void releaseSound(UUID owner) {
        var component = soundOwners.remove(owner);
        if (component == null || soundOwners.containsValue(component)) return;
        var state = soundStates.get(component);
        moveValues.remove(component);
        soundStates.put(component, new IrControlSoundState(false, state.presses(), state.releases() + 1, state.moves()));
        dirty = true;
    }

    /** Discrete buttons and keyboard/scroll handle steps are complete operations, not persistent holds. */
    public void clickSound(String component, float previous, float value) {
        var sound = definition.sounds().controls().get(component);
        var state = soundStates.getOrDefault(component, IrControlSoundState.IDLE);
        if (state.pressed()) return;
        boolean move = sound.move() != null
                && (sound.movePercent() == null || Math.abs(value - previous) > sound.movePercent());
        soundStates.put(component, new IrControlSoundState(false, state.presses() + 1,
                state.releases() + 1, state.moves() + (move ? 1 : 0)));
        dirty = true;
    }

    private BellMode bellMode(VehicleRuntimeState runtime) {
        if (definition.sounds().bell() == null) return BellMode.OFF;
        boolean pulse = false;
        for (String group : definition.sounds().bellGroups()) {
            if (definition.value(group, values, runtime, front, rear) < 0.5f) continue;
            if (!releases.containsKey(group)) return BellMode.SUSTAINED;
            pulse = true;
        }
        return pulse ? BellMode.PULSE : BellMode.OFF;
    }

    /** Only called after the server commits a new coupling, never while restoring a consist. */
    public void coupled(boolean atFront) {
        if (atFront) frontCouplings++;
        else rearCouplings++;
        dirty = true;
    }

    public IrVehicleSnapshot snapshot() {
        return new IrVehicleSnapshot(values, clocks, soundStates, front, rear,
                bellActivations, bellPulse, frontCouplings, rearCouplings);
    }

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
        values.clear(); releases.clear(); heldPresses.clear(); clocks.clear();
        soundStates.clear(); soundOwners.clear(); moveValues.clear();
        front = rear = false;
        bellActive = bellPulse = false;
        bellActivations = frontCouplings = rearCouplings = 0;
        dirty = true;
    }
}
