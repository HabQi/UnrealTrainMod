/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import java.util.List;

/** A compact travel-oriented profile of the driver's independent read-only route preview. */
public record TrainRouteProfile(List<Section> sections) {
    public static final double BEHIND = 200, AHEAD = 800;
    public static final int MAX_SECTIONS = 128;
    public static final TrainRouteProfile EMPTY = new TrainRouteProfile(List.of());

    public TrainRouteProfile {
        if (sections.size() > MAX_SECTIONS) throw new IllegalArgumentException("Route profile exceeds 128 sections");
        sections = List.copyOf(sections);
        double previous = Double.NEGATIVE_INFINITY;
        for (var section : sections) {
            if (section.startOffset() < previous || section.endOffset() < section.startOffset()) {
                throw new IllegalArgumentException("Invalid route profile section order");
            }
            previous = section.endOffset();
        }
    }

    /** Offsets are in blocks, grade is in per mille, and radius is signed; zero means straight. */
    public record Section(double startOffset, double endOffset, double grade, double radius) {
        public Section {
            if (!Double.isFinite(startOffset) || !Double.isFinite(endOffset) || !Double.isFinite(grade)
                    || !Double.isFinite(radius)) {
                throw new IllegalArgumentException("Invalid route profile section");
            }
        }
    }
}
