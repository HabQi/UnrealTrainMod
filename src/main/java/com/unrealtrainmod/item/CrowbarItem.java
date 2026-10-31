/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.entity.TrainEntity;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public final class CrowbarItem extends Item {
    public CrowbarItem(Properties properties) { super(properties); }

    public InteractionResult useOnTrain(Player player, TrainEntity entity) {
        if (player.isSpectator() || !player.mayBuild()) return InteractionResult.FAIL;
        if (!entity.level().isClientSide) {
            entity.setCouplingEnabled(!entity.isCouplingEnabled());
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.crowbar."
                    + (entity.isCouplingEnabled() ? "enabled" : "disabled")), true);
        }
        return InteractionResult.sidedSuccess(entity.level().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.translatable("item.unrealtrainmod.crowbar.tooltip"));
    }
}
