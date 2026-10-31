/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.List;

/** Legacy {@code VehiclePartsRenderer} subset used by the first vehicle script chain. */
public final class VehicleScriptRenderer extends RtmPartsRenderer {
    private ScriptDrawContext context;
    private float wheelRotation;

    void context(ScriptDrawContext value) { context = value; }

    void wheelRotation(float value) { wheelRotation = value; }

    @Override
    protected void draw(List<String> names) {
        if (context == null) throw new IllegalStateException("Parts.render outside a vehicle script render");
        context.draw(names);
    }

    public float getWheelRotationR(Object entity) { return wheelRotation; }

    public float getWheelRotationL(Object entity) { return wheelRotation; }

    // Door, pantograph and light state are not synchronised yet; scripts see the closed/retracted state.
    public float getDoorMovementR(Object entity) { return 0.0f; }

    public float getDoorMovementL(Object entity) { return 0.0f; }

    public float getPantographMovementFront(Object entity) { return 0.0f; }

    public float getPantographMovementBack(Object entity) { return 0.0f; }
}
