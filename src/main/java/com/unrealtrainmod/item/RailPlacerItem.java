/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.track.construction.TrackConstructionManager;
import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import com.unrealtrainmod.track.model.RailCatalog;
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

    public static RailCatalog.Selection selection(ItemStack stack) {
        return new RailCatalog.Selection(selectedModel(stack), stack.get(ModDataComponents.SELECTED_RAIL_VARIANT.get()));
    }

    /** Prefer the held placer, then the hotbar and the main inventory in slot order. */
    public static RailCatalog.Selection constructionSelection(Player player) {
        var placer = UnrealTrainMod.RAIL_PLACER.get();
        if (player.getMainHandItem().is(placer)) return selection(player.getMainHandItem());
        for (var stack : player.getInventory().items) {
            if (stack.is(placer)) return selection(stack);
        }
        return selection(ItemStack.EMPTY);
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
        var selection = selection(stack);
        var id = selection.modelId();
        var definition = RailCatalog.find(id);
        var name = id == null
                ? Component.translatable("item.unrealtrainmod.rail_placer.none")
                : NativeTurnoutCatalog.contains(id)
                    ? Component.translatable(NativeTurnoutCatalog.nameKey(id))
                    : Component.literal(definition == null ? id.toString() : definition.railName());
        if (selection.variant() != null) {
            name = Component.translatable("gui.unrealtrainmod.rail_models.variant." + selection.variant().getSerializedName(), name);
        }
        lines.add(Component.translatable("item.unrealtrainmod.rail_placer.selected", name).withStyle(ChatFormatting.AQUA));
        for (String key : List.of("select", "place", "cancel", "permission")) {
            lines.add(Component.translatable("item.unrealtrainmod.rail_placer." + key).withStyle(ChatFormatting.GRAY));
        }
    }
}
