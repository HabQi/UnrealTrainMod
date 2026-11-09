/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WiringDefinition;
import net.minecraft.world.phys.Vec3;

/** Read-only legacy objects; no Minecraft client or server objects escape through these views. */
public final class WireScriptViews {
    private WireScriptViews() {}
    public static final class Vector {
        private final Vec3 value;
        public Vector(Vec3 value) { this.value = value; }
        public double getX() { return value.x; }
        public double getY() { return value.y; }
        public double getZ() { return value.z; }
        public double length() { return value.length(); }
        public float getYaw() { return (float)Math.toDegrees(Math.atan2(value.x, value.z)); }
        public float getPitch() { return (float)Math.toDegrees(Math.atan2(value.y, Math.hypot(value.x, value.z))); }
        Vec3 nativeVector() { return value; }
    }
    public static final class Tile {
        private final ConnectorData node;
        private final long tick;
        public Tile(ConnectorData node, long tick) { this.node = node; this.tick = tick; }
        public int getX() { return (int)Math.floor(node.anchor().x); }
        public int getY() { return (int)Math.floor(node.anchor().y); }
        public int getZ() { return (int)Math.floor(node.anchor().z); }
        public float getOffsetX() { return (float)node.offset().x; }
        public float getOffsetY() { return (float)node.offset().y; }
        public float getOffsetZ() { return (float)node.offset().z; }
        public float getRotation() { return (float)node.angles().y; }
        public float getRotationX() { return (float)node.angles().x; }
        public float getRotationZ() { return (float)node.angles().z; }
        public float getScale() { return 1; }
        public long getTick() { return tick; }
        public Vector getWirePos() { return new Vector(node.wirePoint().subtract(node.anchor())); }
    }
    public static final class Config {
        public final String name;
        public final float sectionLength, deflectionCoefficient, lengthCoefficient, yOffset;
        public final boolean smoothing, doCulling, useCustomColor;
        public Config(WiringDefinition d) {
            name = d.name(); sectionLength = (float)d.sectionLength(); deflectionCoefficient = (float)d.deflection();
            lengthCoefficient = (float)d.lengthCoefficient(); yOffset = (float)d.yOffset();
            smoothing = d.smoothing(); doCulling = d.culling(); useCustomColor = d.useCustomColor();
        }
        public String getName() { return name; }
    }
    public static final class ResourceSet {
        private final Config config;
        public ResourceSet(WiringDefinition d) { config = new Config(d); }
        public Config getConfig() { return config; }
        public boolean isDummy() { return false; }
    }
    public static final class State {
        public final int color = 0xFFFFFF;
        private final ResourceSet set;
        public State(WiringDefinition d) { set = new ResourceSet(d); }
        public ResourceSet getResourceSet() { return set; }
        public String getResourceName() { return set.getConfig().name; }
    }
    public static final class Connection {
        public final boolean isRoot = true;
        public final int x, y, z;
        private final State state;
        public Connection(WiringDefinition definition, ConnectorData end) {
            state = new State(definition); x = (int)Math.floor(end.anchor().x);
            y = (int)Math.floor(end.anchor().y); z = (int)Math.floor(end.anchor().z);
        }
        public State getResourceState() { return state; }
    }
}
