/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.item;

import com.unrealtrainmod.blueprint.BlueprintDefinition;
import com.unrealtrainmod.registry.ModDataComponents;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public final class BlueprintItem extends Item {
    public BlueprintItem(Properties properties) { super(properties); }
    public static BlueprintDefinition definition(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.BLUEPRINT.get(), BlueprintDefinition.empty());
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown()) return InteractionResultHolder.pass(player.getItemInHand(hand));
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var d = definition(stack);
        lines.add(Component.literal(d.name()).withStyle(net.minecraft.ChatFormatting.AQUA));
        lines.add(Component.translatable(d.alternating() ? "blueprint.alternating" : "blueprint.single"));
        lines.add(Component.translatable("blueprint.item_hint", d.tracks().size()));
    }
}
