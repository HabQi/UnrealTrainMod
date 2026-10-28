/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

/** Heights in blocks, interpolated by the fraction of horizontal arc length. */
public record LinearGrade(double startHeight, double endHeight) {
    public LinearGrade {
        if (!Double.isFinite(startHeight) || !Double.isFinite(endHeight)
                || !Double.isFinite(endHeight - startHeight)) {
            throw new IllegalArgumentException("Track heights and rise must be finite");
        }
    }

    double rise() {
        return endHeight - startHeight;
    }

    double heightAt(double fraction) {
        if (fraction == 0) return startHeight;
        if (fraction == 1) return endHeight;
        return Math.fma(fraction, rise(), startHeight);
    }
}
