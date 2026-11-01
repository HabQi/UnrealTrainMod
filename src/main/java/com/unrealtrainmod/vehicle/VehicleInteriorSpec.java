/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.model.ModelBounds;

/**
 * One rectangular walking plane in vehicle-local coordinates. X/Z are the in-car plane and
 * {@code centerY} is the floor height. Length is the full Z size and width the full X size, in blocks.
 */
public record VehicleInteriorSpec(boolean enabled, double centerX, double centerY, double centerZ,
                                  double length, double width) {
    /** Inset from each side of the authored bounds used by the provisional automatic calibration. */
    public static final double BOUNDS_INSET = 0.5;
    public static final VehicleInteriorSpec DISABLED = new VehicleInteriorSpec(false, 0, 0, 0, 0, 0);

    public VehicleInteriorSpec {
        if (!enabled) {
            centerX = centerY = centerZ = length = width = 0;
        } else {
            finite("centerX", centerX);
            finite("centerY", centerY);
            finite("centerZ", centerZ);
            positive("length", length);
            positive("width", width);
            if (length < 1 || width < 1) throw new IllegalArgumentException("walking area must be at least 1x1 blocks");
        }
    }

    /**
     * Provisional calibration: take the vehicle bounds and move each side inward by
     * {@link #BOUNDS_INSET}. RTM model origins sit on the floor, so the floor defaults to local Y 0.
     */
    public static VehicleInteriorSpec fromBounds(ModelBounds bounds, double floorY) {
        double length = bounds.length() - 2 * BOUNDS_INSET;
        double width = bounds.width() - 2 * BOUNDS_INSET;
        if (!(length >= 1) || !(width >= 1)) return DISABLED;
        return new VehicleInteriorSpec(true, bounds.centerX(), floorY, bounds.centerZ(), length, width);
    }

    public double minX() { return centerX - width / 2; }
    public double maxX() { return centerX + width / 2; }
    public double minZ() { return centerZ - length / 2; }
    public double maxZ() { return centerZ + length / 2; }
    public double clampX(double x) { return Math.clamp(x, minX(), maxX()); }
    public double clampZ(double z) { return Math.clamp(z, minZ(), maxZ()); }
    public boolean contains(double x, double z) { return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ(); }

    private static void positive(String field, double value) {
        finite(field, value);
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void finite(String field, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}
