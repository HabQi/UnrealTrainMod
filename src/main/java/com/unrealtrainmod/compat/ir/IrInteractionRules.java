/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.UnrealTrainMod;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class IrInteractionRules {
    public static final double REACH = 4;
    private IrInteractionRules() {}

    public static boolean holdingKey(Player player) {
        return player.getMainHandItem().is(UnrealTrainMod.KEY.get()) || player.getOffhandItem().is(UnrealTrainMod.KEY.get());
    }

    public static boolean inReach(Vec3 eye, AABB bounds) {
        double x = Math.clamp(eye.x, bounds.minX, bounds.maxX) - eye.x;
        double y = Math.clamp(eye.y, bounds.minY, bounds.maxY) - eye.y;
        double z = Math.clamp(eye.z, bounds.minZ, bounds.maxZ) - eye.z;
        return x * x + y * y + z * z <= REACH * REACH;
    }
}
