/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.train.TrainManager;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.joml.Vector3f;

public final class VehicleMoverItem extends Item {
    public VehicleMoverItem(Properties properties) { super(properties); }

    public InteractionResult useOnTrain(Player player, TrainEntity entity) {
        if (player.isSpectator() || !player.mayBuild()) return InteractionResult.FAIL;
        if (!(entity.level() instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        var look = player.getLookAngle();
        var forward = VehiclePose.Body.rotation(entity.getYRot(), entity.getXRot()).transform(new Vector3f(0, 0, 1));
        double projection = look.x * forward.x + look.z * forward.z;
        if (Math.abs(projection) < 0.1) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.vehicle_mover.direction"), true);
            return InteractionResult.CONSUME;
        }
        var failure = TrainManager.get(server).nudge(entity, projection > 0);
        player.displayClientMessage(Component.translatable(failure == null ? "item.unrealtrainmod.vehicle_mover.moved"
                : "commands.unrealtrainmod.train.state." + failure.name().toLowerCase(Locale.ROOT)), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.translatable("item.unrealtrainmod.vehicle_mover.tooltip"));
    }
}
