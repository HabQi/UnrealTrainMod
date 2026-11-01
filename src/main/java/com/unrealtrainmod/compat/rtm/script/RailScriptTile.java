/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import net.minecraft.core.BlockPos;

/** Minimal tile-entity view exposed to legacy rail scripts. */
public final class RailScriptTile {
    public static final class Property {
        public final String railModel;

        private Property(String railModel) { this.railModel = railModel; }
    }

    private final Property property;
    private final int[] startPoint = new int[3];

    public RailScriptTile(String railModel) {
        property = new Property(railModel == null ? "" : railModel);
    }

    public Property getProperty() { return property; }

    /** Whole-segment start block, shared by all of its chunk meshes; previews use the origin. */
    public int[] getStartPoint() { return startPoint; }

    void setStartPoint(BlockPos position) {
        startPoint[0] = position.getX();
        startPoint[1] = position.getY();
        startPoint[2] = position.getZ();
    }

    /** Normal-track static filtering has no switch; dynamic switch support is a later stage. */
    public Object getSwitch() { return null; }
}
