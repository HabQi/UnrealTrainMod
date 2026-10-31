/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.track.construction.TurnoutConstructionManager;
import com.unrealtrainmod.track.construction.TrackConstructionType;
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
    private final TrackConstructionType type;
    public TurnoutBuilderItem(TrackConstructionType type, Properties properties) {
        super(properties);
        this.type = type;
    }
    public TrackConstructionType constructionType() { return type; }
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
        if (!type.implemented()) {
            lines.add(Component.translatable("item.unrealtrainmod.turnout_builder.unimplemented").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("item.unrealtrainmod.turnout_builder.cancel").withStyle(ChatFormatting.GRAY));
            return;
        }
        for (String key : List.of("select", "preview", "cancel", "permission")) {
            String prefix = type == TrackConstructionType.DIAMOND_CROSSING ? "diamond_crossing_builder" : "turnout_builder";
            lines.add(Component.translatable("item.unrealtrainmod." + prefix + "." + key).withStyle(ChatFormatting.GRAY));
        }
    }
}
