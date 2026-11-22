/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.resource.PackageAsset;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** Per-runtime render snapshot. Never retains a native entity or its world. */
public final class RtmVehicleView {
    private static final RenderWorld CLIENT_WORLD = new RenderWorld();

    public final RenderWorld field_70170_p = CLIENT_WORLD;
    public final int seatRotation = 0;
    public final int pantograph_B = 40;

    private final PackageAsset script;
    private final ResourceState resourceState = new ResourceState();
    private final Formation formation = new Formation(this);
    private int entityId, tick, light;
    private float speed, wheelRotation, doorLeft, doorRight;
    private int notch, reverser, doorState;

    RtmVehicleView(PackageAsset script) { this.script = script; }

    void update(int entityId, int tick, int light, float speed, int notch, int reverser, int doorState,
                float wheelRotation, float doorLeft, float doorRight, int formationIndex, int formationSize) {
        this.entityId = entityId;
        this.tick = tick;
        this.light = light;
        this.speed = speed;
        this.notch = notch;
        this.reverser = reverser;
        this.doorState = doorState;
        this.wheelRotation = wheelRotation;
        this.doorLeft = doorLeft;
        this.doorRight = doorRight;
        formation.update(formationIndex, formationSize);
    }

    int tick() { return tick; }
    float wheelRotation() { return wheelRotation; }
    float doorLeft() { return doorLeft; }
    float doorRight() { return doorRight; }

    /** Signed blocks/tick relative to the owning vehicle's front; multiply by 72 for km/h. */
    public float getSpeed() { return speed; }

    public int getNotch() { return notch; }

    /** Legacy two-way selector: 0 = front (also native neutral), 1 = back. */
    public int getTrainDirection() { return reverser < 0 ? 1 : 0; }

    public byte getTrainStateData(int id) { return getVehicleState(RtmTrainState.getStateType(id)); }

    public byte getVehicleState(RtmTrainState.TrainStateType type) {
        return switch (type) {
            case State_TrainDir -> (byte)getTrainDirection();
            case State_Notch -> (byte)notch;
            case State_Door -> (byte)doorState;
            case State_Announcement -> {
                ScriptDiagnostics.temporaryAnnouncement(script);
                yield 1;
            }
            case State_Direction -> (byte)(1 - reverser);
        };
    }

    public Formation getFormation() { return formation.size() == 0 ? null : formation; }
    public ResourceState getResourceState() { return resourceState; }

    /** Legacy entity ID and JavaBean property for the obfuscated entity tick. */
    public int func_145782_y() { return entityId; }
    public int getField_70173_aa() { return tick; }

    /** Obfuscated 1.12.2 packed-light query used by legacy interior-light scripts. */
    public int func_70070_b() { return light; }

    public static final class RenderWorld {
        public final boolean field_72995_K = true;
    }

    public static final class ResourceState {
        private final DataMap data = new DataMap();

        public DataMap getDataMap() { return data; }
    }

    /** Legacy render-side values are local to this script instance. */
    public static final class DataMap {
        private final CompoundTag values = new CompoundTag();

        public int getInt(String key) { return values.getInt(key); }
        public boolean getBoolean(String key) { return values.getBoolean(key); }
        public void setBoolean(String key, boolean value, int syncMode) { values.putBoolean(key, value); }
    }

    /** Entries describe server-known membership even when a neighbor has no client entity. */
    public static final class Formation {
        private final RtmVehicleView owner;
        private final Map<Integer, FormationEntry> entries = new HashMap<>();
        private int index, size;

        private Formation(RtmVehicleView owner) { this.owner = owner; }

        private void update(int index, int size) {
            this.index = index;
            if (this.size != size) entries.clear();
            this.size = size;
        }

        public int size() { return size; }
        public FormationEntry get(int index) {
            return index < 0 || index >= size ? null : entries.computeIfAbsent(index, FormationEntry::new);
        }
        public FormationEntry getEntry(RtmVehicleView entity) { return entity == owner ? get(index) : null; }
    }

    public static final class FormationEntry {
        public final int entryId;
        public final boolean train = true;

        private FormationEntry(int entryId) { this.entryId = entryId; }
    }
}
