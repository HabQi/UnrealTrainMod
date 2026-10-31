/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.train.TrainManager;
import com.unrealtrainmod.train.TrainConsist.StopReason;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public final class CrowbarItem extends Item {
    public CrowbarItem(Properties properties) { super(properties); }

    public InteractionResult useOnTrain(Player player, TrainEntity entity) {
        if (player.isSpectator() || !player.mayBuild()) return InteractionResult.FAIL;
        if (entity.level() instanceof ServerLevel server) {
            try {
                var end = TrainManager.nearestCoupler(entity, player.position());
                var manager = TrainManager.get(server);
                StopReason failure = null;
                String action;
                if (manager.couplingPeer(entity, end) != null) {
                    failure = manager.uncouple(entity, end);
                    action = "uncoupled";
                } else {
                    entity.setCouplingEnabled(end, !entity.isCouplingEnabled(end));
                    action = entity.isCouplingEnabled(end) ? "enabled" : "disabled";
                }
                var endName = Component.translatable("item.unrealtrainmod.crowbar.end." + end.name().toLowerCase(Locale.ROOT));
                player.displayClientMessage(failure == null
                        ? Component.translatable("item.unrealtrainmod.crowbar." + action, endName)
                        : Component.translatable("commands.unrealtrainmod.train.state." + failure.name().toLowerCase(Locale.ROOT)), true);
            } catch (TrainManager.StateException exception) {
                player.displayClientMessage(Component.translatable("commands.unrealtrainmod.train.state."
                        + exception.reason().name().toLowerCase(Locale.ROOT)), true);
            }
        }
        return InteractionResult.sidedSuccess(entity.level().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.translatable("item.unrealtrainmod.crowbar.tooltip"));
    }
}
