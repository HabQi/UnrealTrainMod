/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.List;

/** Legacy {@code RailPartsRenderer} subset used while the static chunk mesh is generated. */
public final class RailScriptRenderer extends RtmPartsRenderer {
    @Override
    protected void draw(List<String> names) {
        throw new UnsupportedOperationException(
                "Parts.render is not available during static rail filtering; use shouldRenderObject");
    }

    /** Normal-track meshes are not switch rails; dynamic switch tongues are a later stage. */
    public boolean isSwitchRail(Object tile) { return false; }

    /** The mesh builder runs shouldRenderObject for every repeated module instead of replaying draws. */
    public void renderStaticParts(Object tile, double x, double y, double z) {}

    public int getBrightness(Object world, double x, double y, double z) { return 0; }

    public void setBrightness(int brightness) {}

    public Object getWorld(Object tile) { return null; }

    public double getY(Object tile) { return 0; }
}
