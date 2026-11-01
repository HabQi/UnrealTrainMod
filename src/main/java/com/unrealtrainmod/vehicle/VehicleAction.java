/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;

/**
 * One legacy RTM {@code VehicleParts} action node. Transforms are multiplied by the action progress and
 * applied in array order around {@code pivot}, matching the original basic vehicle renderer.
 */
public record VehicleAction(List<String> objects, TrackVector pivot, List<Transform> transforms,
                            List<VehicleAction> children) {
    public VehicleAction {
        objects = List.copyOf(objects);
        transforms = List.copyOf(transforms);
        children = List.copyOf(children);
    }

    public sealed interface Transform permits Translate, Rotate {}

    /** Translation in blocks, scaled by the animation progress. */
    public record Translate(float x, float y, float z) implements Transform {}

    /** Rotation in degrees around the normalised axis, scaled by the animation progress. */
    public record Rotate(float angle, float x, float y, float z) implements Transform {}
}
