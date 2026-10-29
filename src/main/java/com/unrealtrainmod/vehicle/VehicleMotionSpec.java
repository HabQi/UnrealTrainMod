/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.model.ModelBounds;

/**
 * Motion geometry in blocks, independent of render bounds. The authored origin is retained;
 * +Y is up and +Z is front. Coupler and bogie offsets are signed local Z coordinates.
 * trackDatum moves the model origin from the rail surface along its normal.
 */
public record VehicleMotionSpec(double length, double width, double height, double trackDatum,
                                double frontCouplerOffset, double rearCouplerOffset,
                                double frontBogieOffset, double rearBogieOffset,
                                double defaultCouplerGap) {
    public VehicleMotionSpec {
        positive("length", length);
        positive("width", width);
        positive("height", height);
        finite("trackDatum", trackDatum);
        finite("frontCouplerOffset", frontCouplerOffset);
        finite("rearCouplerOffset", rearCouplerOffset);
        finite("frontBogieOffset", frontBogieOffset);
        finite("rearBogieOffset", rearBogieOffset);
        finite("defaultCouplerGap", defaultCouplerGap);
        if (frontCouplerOffset <= rearCouplerOffset) {
            throw new IllegalArgumentException("frontCouplerOffset must be greater than rearCouplerOffset");
        }
        if (frontBogieOffset <= rearBogieOffset) {
            throw new IllegalArgumentException("frontBogieOffset must be greater than rearBogieOffset");
        }
        if (defaultCouplerGap < 0) throw new IllegalArgumentException("defaultCouplerGap must not be negative");
    }

    /** Approximate geometry for packages without native motion parameters; may need calibration. */
    public static VehicleMotionSpec fromBounds(ModelBounds bounds, double trackDatum) {
        double length = bounds.length();
        double center = bounds.centerZ();
        return new VehicleMotionSpec(length, bounds.width(), bounds.height(), trackDatum,
                bounds.maxZ(), bounds.minZ(), center + 0.3 * length, center - 0.3 * length, 0);
    }

    private static void positive(String field, double value) {
        finite(field, value);
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void finite(String field, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}
