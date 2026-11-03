/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.client.model.LoadedModel;
import java.util.List;

/** Legacy {@code VehiclePartsRenderer} subset used by the first vehicle script chain. */
public final class VehicleScriptRenderer extends RtmPartsRenderer {
    private ScriptDrawContext context;
    private final ScriptPartResolver parts;

    VehicleScriptRenderer(LoadedModel.Source source, String modelLabel) {
        parts = new ScriptPartResolver(source, modelLabel);
    }

    void context(ScriptDrawContext value) { context = value; }

    ScriptPartResolver parts() { return parts; }

    @Override
    void release() {
        context = null;
        parts.clear();
        super.release();
    }

    @Override
    protected void registered(Parts group) { group.names().forEach(parts::selection); }

    @Override
    protected void draw(List<String> names) {
        if (context == null) throw new IllegalStateException("Parts.render outside a vehicle script render");
        context.draw(names);
    }

    public float getWheelRotationR(Object entity) {
        return entity instanceof RtmVehicleView view ? view.wheelRotation() : 0;
    }

    public int getTick(Object entity) { return entity instanceof RtmVehicleView view ? view.tick() : 0; }

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

    public float getWheelRotationL(Object entity) { return getWheelRotationR(entity); }

    /** Raw RTM progress in {@code 0.0..1.0}; the configuration renderer applies its own easing. */
    public float getDoorMovementR(Object entity) {
        return entity instanceof RtmVehicleView view ? view.doorRight() : 0;
    }

    public float getDoorMovementL(Object entity) {
        return entity instanceof RtmVehicleView view ? view.doorLeft() : 0;
    }

    public float getPantographMovementFront(Object entity) { return 0.0f; }

    public float getPantographMovementBack(Object entity) { return 0.0f; }
}
