/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/** Minimal tile-entity view exposed to legacy rail scripts. */
public final class RailScriptTile {
    public static final class Property {
        public final String railModel;

        private Property(String railModel) { this.railModel = railModel; }
    }

    private final Property property;

    public RailScriptTile(String railModel) {
        property = new Property(railModel == null ? "" : railModel);
    }

    public Property getProperty() { return property; }

    /** Normal-track static filtering has no switch; dynamic switch support is a later stage. */
    public Object getSwitch() { return null; }
}
