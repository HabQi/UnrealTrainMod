/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.item;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.blueprint.BlueprintManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class BlueprintPlacerItem extends Item {
    public BlueprintPlacerItem(Properties properties) { super(properties); }

    public static ItemStack blueprint(ItemStack placer) {
        var contents = placer.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (contents.getSlots() != 1) return ItemStack.EMPTY;
        var stack = contents.copyOne();
        return stack.getCount() == 1 && stack.is(UnrealTrainMod.BLUEPRINT.get()) ? stack : ItemStack.EMPTY;
    }

    public static Component blueprintName(ItemStack blueprint) {
        String name = BlueprintItem.definition(blueprint).name();
        return name.isBlank() ? blueprint.getHoverName() : Component.literal(name);
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack carried, Slot slot, ClickAction action,
                                            Player player, SlotAccess cursor) {
        if (action != ClickAction.SECONDARY || stack.getCount() != 1 || !slot.allowModification(player)) return false;
        var loaded = blueprint(stack);
        if (carried.isEmpty() && !loaded.isEmpty()) {
            if (cursor.set(loaded)) {
                stack.remove(DataComponents.CONTAINER);
                slot.setChanged();
            }
            return true;
        }
        if (!carried.is(UnrealTrainMod.BLUEPRINT.get())) return false;
        if (stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).getSlots() == 0) {
            stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(carried.split(1))));
            slot.setChanged();
        }
        return true;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) {
            if (player.isShiftKeyDown()) BlueprintManager.cancel(player);
            else BlueprintManager.pick(player, context);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
        if (player.isShiftKeyDown() && player instanceof ServerPlayer server) BlueprintManager.cancel(server);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var loaded = blueprint(stack);
        if (loaded.isEmpty()) {
            lines.add(Component.translatable("blueprint.placer.load").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("blueprint.placer.loaded", blueprintName(loaded)).withStyle(ChatFormatting.AQUA));
            lines.add(Component.translatable("blueprint.placer.use").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("blueprint.placer.unload").withStyle(ChatFormatting.GRAY));
        }
    }
}
