/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.preview;

/** Per-screen camera, independent of the player and world entities. */
public final class PreviewCamera {
    private float yaw = -135;
    private float pitch = 20;
    private float zoom = 1;
    private float panX;
    private float panY;

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float zoom() { return zoom; }
    public float panX() { return panX; }
    public float panY() { return panY; }

    public void setYaw(double yaw) { this.yaw = (float)yaw; }
    public void setZoom(double zoom) { this.zoom = (float)Math.clamp(Math.round(zoom * 20) / 20.0, 0.5, 2.0); }

    public void rotate(double x, double y) {
        yaw = net.minecraft.util.Mth.wrapDegrees(yaw + (float)x);
        pitch = Math.clamp(pitch + (float)y, -85, 85);
    }

    public void pan(float x, float y) {
        panX += x;
        panY += y;
    }

    public void reset() {
        yaw = -135;
        pitch = 20;
        zoom = 1;
        panX = panY = 0;
    }
}
