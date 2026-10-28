/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.track.construction.TrackConstructionManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

public final class RailPlacerItem extends Item {
    public RailPlacerItem(Properties properties) { super(properties); }

    public static ResourceLocation selectedModel(ItemStack stack) {
        return stack.get(ModDataComponents.SELECTED_RAIL_MODEL.get());
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (context.getPlayer() instanceof ServerPlayer player) TrackConstructionManager.place(player);
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.fail(player.getItemInHand(hand));
        if (player instanceof ServerPlayer serverPlayer && (player.isShiftKeyDown() || !player.hasPermissions(2))) {
            TrackConstructionManager.place(serverPlayer);
        }
        // Ordinary air use opens the client selector. It never places the pending line.
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var id = selectedModel(stack);
        var definition = RailCatalog.find(id);
        lines.add(Component.translatable("item.unrealtrainmod.rail_placer.selected", id == null
                ? Component.translatable("item.unrealtrainmod.rail_placer.none")
                : Component.literal(definition == null ? id.toString() : definition.railName())).withStyle(ChatFormatting.AQUA));
        for (String key : List.of("select", "place", "cancel", "permission")) {
            lines.add(Component.translatable("item.unrealtrainmod.rail_placer." + key).withStyle(ChatFormatting.GRAY));
        }
    }
}
