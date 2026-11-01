/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.vehicle;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only RTM door actions attached to a vehicle body model. */
public record VehicleActions(List<VehicleAction> leftDoors, List<VehicleAction> rightDoors) {
    public static final VehicleActions NONE = new VehicleActions(List.of(), List.of());

    public VehicleActions {
        leftDoors = List.copyOf(leftDoors);
        rightDoors = List.copyOf(rightDoors);
    }

    public boolean isEmpty() { return leftDoors.isEmpty() && rightDoors.isEmpty(); }

    public List<VehicleAction> doors(VehicleDoor side) {
        return side == VehicleDoor.LEFT ? leftDoors : rightDoors;
    }

    /** Lower-case trimmed object names of every action node, including children. */
    public void collectPartNames(Set<String> destination) {
        collect(leftDoors, destination);
        collect(rightDoors, destination);
    }

    private static void collect(List<VehicleAction> actions, Set<String> destination) {
        for (var action : actions) {
            for (String name : action.objects()) destination.add(key(name));
            collect(action.children(), destination);
        }
    }

    public static String key(String name) { return name.trim().toLowerCase(Locale.ROOT); }
}
