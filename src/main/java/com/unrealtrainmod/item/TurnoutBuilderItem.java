/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.track.construction.TurnoutConstructionManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class TurnoutBuilderItem extends Item {
    public TurnoutBuilderItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (context.getPlayer() instanceof ServerPlayer player) TurnoutConstructionManager.interact(player, context);
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.fail(player.getItemInHand(hand));
        if (player instanceof ServerPlayer serverPlayer) TurnoutConstructionManager.interact(serverPlayer, null);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        for (String key : List.of("select", "preview", "cancel", "permission")) {
            lines.add(Component.translatable("item.unrealtrainmod.turnout_builder." + key).withStyle(ChatFormatting.GRAY));
        }
    }
}
