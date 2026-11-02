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

    public int getTick(RtmVehicleView entity) { return entity == null ? 0 : entity.tick(); }

    public void rotate(float angle, char axis, float x, float y, float z) {
        if (context == null) throw new IllegalStateException("renderer.rotate outside a vehicle script render");
        context.translate(x, y, z);
        switch (axis) {
            case 'X' -> context.rotate(angle, 1, 0, 0);
            case 'Y' -> context.rotate(angle, 0, 1, 0);
            case 'Z' -> context.rotate(angle, 0, 0, 1);
            default -> { }
        }
        context.translate(-x, -y, -z);
    }

    public float getWheelRotationL(Object entity) { return wheelRotation; }

    /** Raw RTM progress in {@code 0.0..1.0}; the configuration renderer applies its own easing. */
    public float getDoorMovementR(Object entity) { return doorMovementRight; }

    public float getDoorMovementL(Object entity) { return doorMovementLeft; }

    public float getPantographMovementFront(Object entity) { return 0.0f; }

    public float getPantographMovementBack(Object entity) { return 0.0f; }
}
