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
    private float doorMovementLeft, doorMovementRight;

    void context(ScriptDrawContext value) { context = value; }

    void wheelRotation(float value) { wheelRotation = value; }

    void doorMovement(float left, float right) {
        doorMovementLeft = left;
        doorMovementRight = right;
    }

    @Override
    protected void draw(List<String> names) {
        if (context == null) throw new IllegalStateException("Parts.render outside a vehicle script render");
        context.draw(names);
    }

    public float getWheelRotationR(Object entity) { return wheelRotation; }

    public float getWheelRotationL(Object entity) { return wheelRotation; }

    /** Raw RTM progress in {@code 0.0..1.0}; the configuration renderer applies its own easing. */
    public float getDoorMovementR(Object entity) { return doorMovementRight; }

    public float getDoorMovementL(Object entity) { return doorMovementLeft; }

    public float getPantographMovementFront(Object entity) { return 0.0f; }

    public float getPantographMovementBack(Object entity) { return 0.0f; }
}
